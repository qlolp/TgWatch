#!/usr/bin/env python3
"""Unauthenticated regional evidence from this computer; no account or feature readiness claim."""
import argparse
import hashlib
from html.parser import HTMLParser
import json
import multiprocessing
import re
import signal
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

PROFILES = {
    'youtube': ('YouTube', 'https://www.youtube.com/', {'www.youtube.com', 'youtube.com'}),
    'gemini': ('Gemini', 'https://gemini.google.com/', {'gemini.google.com'}),
    'notebook': ('Notebook', 'https://notebook.google.com/', {'notebook.google.com', 'notebooklm.google.com'}),
    'antigravity': ('Antigravity', 'https://antigravity.google/',
                    {'antigravity.google', 'www.antigravity.google', 'antigravity.google.com'}),
    'dots': ('ChatGPT / dots', 'https://chatgpt.com/dots/home', {'chatgpt.com'}),
}
LOGIN_HOSTS = {'accounts.google.com', 'auth.openai.com', 'auth0.openai.com',
               'consent.google.com', 'consent.youtube.com'}
REGION_CODES = {'unsupported_country_region_territory', 'unsupported_country',
                'country_not_supported', 'region_not_supported', 'unsupported_location', 'region_unsupported'}
SERVICE_NAME = r'(?:this service|the service|gemini|youtube|notebook|notebooklm|gemini notebook|antigravity|chatgpt)'
ENGLISH_ERROR = re.compile(r'^(?:' + SERVICE_NAME + r"\s+)?(?:is\s+)?(?:currently\s+)?"
    r"(?:not available|not supported|unavailable|isn't available|isn't supported) in your (?:country|region)"
    r'(?: yet| at this time| right now)?[.!]?$')
RUSSIAN_ERROR = re.compile(r'^(?:(?:gemini|youtube|notebook|gemini notebook|antigravity|chatgpt|'
    r'сервис|этот сервис)\s+)?(?:пока\s+)?(?:недоступен|недоступно|не поддерживается) в '
    r'(?:вашей стране|вашем регионе)[.!]?$')
CHALLENGES = ('verify you are human', 'unusual traffic', 'checking your browser',
              'подтвердите, что вы человек', 'подозрительный трафик')
IGNORED = re.compile(r'<(script|style|head|template|noscript|pre|code|blockquote|q|article)\b[^>]*>'
                     r'(?:.*?</\1\s*>|.*\Z)', re.I | re.S)
RENDERING = re.compile(r'<(?:script|style|link)\b', re.I)
COMMENTS = re.compile(r'<!--.*?(?:-->|$)', re.S)
HEADINGS = re.compile(r'<(h1|h2)\b[^>]*>(.*?)</\1\s*>', re.I | re.S)
TAGS = re.compile(r'<[^>]*>')
ISO_COUNTRIES = frozenset('AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ '
    'BR BS BT BV BW BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH '
    'ER ES ET FI FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE '
    'IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC '
    'MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE '
    'PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV '
    'SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT '
    'ZA ZM ZW'.split())


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key')
        result[key] = value
    return result


def validate_json(value, depth=0):
    if isinstance(value, (dict, list)):
        if depth >= 10:
            raise ValueError('JSON nesting limit')
        children = list(value.keys()) + list(value.values()) if isinstance(value, dict) else value
        for child in children:
            validate_json(child, depth + 1)
    elif isinstance(value, str):
        value.encode('utf-8', errors='strict')


class VisibleMarkup(HTMLParser):
    """Hidden subtrees never contribute regional evidence, including nested hidden containers."""
    VOID = {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr'}

    def __init__(self):
        super().__init__(convert_charrefs=False)
        self.stack = []
        self.parts = []
        self.invalid = False

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        hidden = (bool(self.stack and self.stack[-1][1]) or 'hidden' in attrs or
                  attrs.get('aria-hidden', '').lower() == 'true' or bool(re.search(
                      r'display\s*:\s*none|visibility\s*:\s*hidden', attrs.get('style', ''), re.I)))
        if len(self.stack) > 64:
            hidden = True
            self.invalid = True
        if not hidden:
            self.parts.append(self.get_starttag_text())
        if tag not in self.VOID:
            self.stack.append((tag, hidden))

    def handle_endtag(self, tag):
        hidden = bool(self.stack and self.stack[-1][1])
        for index in range(len(self.stack) - 1, -1, -1):
            if self.stack[index][0] == tag:
                del self.stack[index:]
                break
        if not hidden:
            self.parts.append(f'</{tag}>')

    def handle_data(self, data):
        if not self.stack or not self.stack[-1][1]:
            self.parts.append(data)

    def handle_entityref(self, name):
        self.handle_data('&' + name + ';')

    def handle_charref(self, name):
        self.handle_data('&#' + name + ';')


def classify(code, content_type, body, location_host='', complete=True):
    if code == 401 or 300 <= code <= 399 and location_host.lower() in LOGIN_HOSTS:
        return 'AUTH_REQUIRED'
    if code == 429:
        return 'RATE_LIMITED'
    if not (200 <= code <= 299 or 400 <= code <= 499):
        return 'UNKNOWN'
    if content_type.split(';')[0].strip().lower() == 'application/json':
        if not complete:
            return 'UNKNOWN'
        try:
            value = json.loads(body, object_pairs_hook=unique_object,
                               parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Invalid constant')))
            validate_json(value)
            if not isinstance(value, dict):
                return 'UNKNOWN'
            error = value.get('error')
            if isinstance(error, dict):
                error_code = error.get('code')
                return 'REGION_RESTRICTED' if isinstance(error_code, str) and error_code.lower() in REGION_CODES else 'UNKNOWN'
        except (ValueError, RecursionError):
            return 'UNKNOWN'
    markup = VisibleMarkup()
    uncommented = COMMENTS.sub(' ', body)
    markup.feed(IGNORED.sub(' ', uncommented))
    filtered = '' if markup.invalid else ''.join(markup.parts)
    def text(value):
        return ' '.join(TAGS.sub(' ', value).replace('&nbsp;', ' ').replace('&#39;', "'")
                        .replace('’', "'").lower().split())
    def direct_error(value):
        return bool(ENGLISH_ERROR.fullmatch(value) or RUSSIAN_ERROR.fullmatch(value))
    visible = text(filtered)
    heading = any(direct_error(text(match[1])) for match in HEADINGS.findall(filtered))
    if complete and any(phrase in visible for phrase in CHALLENGES):
        return 'CHALLENGE'
    if complete and len(visible) <= 4096 and (heading or 400 <= code <= 499 and direct_error(visible)):
        return 'UNKNOWN' if RENDERING.search(uncommented) else 'REGION_RESTRICTED'
    return 'WEB_REACHABLE' if 200 <= code <= 299 else 'UNKNOWN'


class Consensus:
    def __init__(self):
        self.scope = None
        self.last = -1
        self.first = None
        self.confirmed = False
        self.notified = False
        self.last_notification = None

    def update(self, status, scope, now):
        if scope != self.scope or now < self.last:
            self.first, self.confirmed, self.notified = None, False, False
            if now < self.last:
                self.last_notification = None
        self.scope, self.last = scope, now
        if status == 'REGION_RESTRICTED':
            if self.first is None:
                self.first = now
            self.confirmed = now - self.first >= 15
        else:
            self.first, self.confirmed = None, False
            if status == 'WEB_REACHABLE':
                self.notified = False

    def should_notify(self, now):
        return self.confirmed and not self.notified and (
            self.last_notification is None or now - self.last_notification >= 300)

    def delivered(self, now):
        self.notified, self.last_notification = True, now


def allowed_redirect(service, url):
    try:
        parsed = urllib.parse.urlsplit(url)
        return parsed.scheme == 'https' and parsed.hostname in PROFILES[service][2] and (
            parsed.username is None and parsed.password is None and parsed.port in (None, 443))
    except ValueError:
        return False


def read_bounded(stream, limit):
    result = bytearray()
    while len(result) <= limit:
        chunk = stream.read(min(2048, limit + 1 - len(result)))
        if not chunk:
            return bytes(result), True
        result.extend(chunk)
    return bytes(result[:limit]), False


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def http_get(url, limit=32768):
    # No cookie jar, credentials, environment proxies or automatic redirects. Uses the OS route (including VPN).
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect,
        urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    request = urllib.request.Request(url, headers={'Accept': 'text/html, application/json',
                                                   'Accept-Encoding': 'identity', 'Cookie': ''})
    try:
        response = opener.open(request, timeout=3)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body, complete = read_bounded(response, limit)
        location = response.headers.get('Location', '')
        return response.code, response.headers.get('Content-Type', ''), body.decode('utf-8', errors='replace'), (
            location if len(location) <= 2048 else ''), complete


def public_probe(service):
    url = PROFILES[service][1]
    for hop in range(3):
        code, content_type, body, location, complete = http_get(url)
        if 300 <= code <= 399:
            next_url = urllib.parse.urljoin(url, location)
            status = classify(code, '', '', urllib.parse.urlsplit(next_url).hostname or '')
            if status == 'AUTH_REQUIRED':
                return status, code
            if not location or hop == 2 or not allowed_redirect(service, next_url):
                return 'UNKNOWN', code
            url = next_url
        else:
            return classify(code, content_type, body, complete=complete), code
    return 'UNKNOWN', None


def route_scope():
    """Ephemeral hash only. Neither network diagnostics nor their contents enter reports."""
    command = ['/usr/sbin/scutil', '--nwi'] if sys.platform == 'darwin' else ['/sbin/ip', 'route', 'show', 'table', 'all']
    try:
        result = subprocess.run(command, capture_output=True, timeout=3, check=True)
        return hashlib.sha256(result.stdout).hexdigest()
    except (OSError, subprocess.SubprocessError):
        return None


def country_context():
    code, _, body, _, complete = http_get('https://www.cloudflare.com/cdn-cgi/trace', 8192)
    if code != 200 or not complete:
        return None, None
    fields = dict(line.split('=', 1) for line in body.splitlines() if '=' in line)
    country = fields.get('loc', '').upper()
    # The address is only used in volatile memory to notice an egress change. It is never returned or persisted.
    fingerprint = hashlib.sha256(fields['ip'].encode()).hexdigest() if fields.get('ip') else None
    return country if country in ISO_COUNTRIES else None, fingerprint


def worker(kind, service, connection):
    try:
        value = country_context() if kind == 'country' else public_probe(service)
    except (OSError, ValueError, urllib.error.URLError):
        value = (None, None) if kind == 'country' else ('NETWORK_ERROR', None)
    try:
        connection.send(value)
    finally:
        connection.close()


def bounded_probe(kind, service=None):
    """An owned subprocess makes the 9s deadline apply to DNS and trickling responses too."""
    context = multiprocessing.get_context('spawn')
    parent, child = context.Pipe(duplex=False)
    process = context.Process(target=worker, args=(kind, service, child), daemon=True)
    process.start()
    child.close()
    try:
        if parent.poll(9):
            return parent.recv()
        return (None, None) if kind == 'country' else ('NETWORK_ERROR', None)
    except EOFError:
        return (None, None) if kind == 'country' else ('NETWORK_ERROR', None)
    finally:
        parent.close()
        if process.is_alive():
            process.terminate()
        process.join(timeout=1)
        if process.is_alive():
            process.kill()
            process.join()


def safe_report(service, status, code, country, checked_at):
    return dict(source='mac_public' if sys.platform == 'darwin' else 'computer_public', service=service,
                status=status, http_code=code, country=country, checked_at=checked_at)


class AppEvidence:
    """Private IPC identity is random, not a route/IP fingerprint. Every context change resets all profiles."""
    def __init__(self, services):
        self.services = tuple(services)
        self.scope = None
        self.context_id = None
        self.states = {service: Consensus() for service in services}
        self.statuses = {}
        self.web_first = {}
        self.last = -1

    def update(self, service, status, scope, now):
        if scope is None or scope != self.scope or now < self.last:
            self.scope = scope
            self.context_id = uuid.uuid4().hex if scope is not None else None
            self.states = {item: Consensus() for item in self.services}
            self.statuses = {}
            self.web_first = {}
        self.last = now
        state = self.states[service]
        if scope is not None:
            state.update(status, self.context_id, now)
            if status == 'WEB_REACHABLE':
                if self.statuses.get(service) != status:
                    self.web_first[service] = now
                elif service in self.web_first and now - self.web_first[service] >= 15:
                    self.web_first.pop(service)
            else:
                self.web_first.pop(service, None)
            self.statuses[service] = status
        return self.context_id, state

    def next_delay(self, service, now, interval):
        if self.context_id is None:
            return interval
        state = self.states[service]
        if self.statuses.get(service) == 'REGION_RESTRICTED' and not state.confirmed and state.first is not None:
            return max(0.1, 15 - (now - state.first))
        if service in self.web_first:
            return max(0.1, 15 - (now - self.web_first[service]))
        return interval


def app_report(service, status, code, country, checked_at, context_id, confirmed):
    report = safe_report(service, status if context_id is not None else 'UNKNOWN', code,
                         country if context_id is not None else None, checked_at)
    report.update(schema=1, region_confirmed=bool(confirmed and context_id is not None), context_id=context_id)
    return report


def measure(service):
    before = route_scope()
    country, egress = bounded_probe('country')
    status, code = bounded_probe('service', service)
    current_country, current_egress = bounded_probe('country')
    after = route_scope()
    changed = before != after or (egress is not None and current_egress is not None and egress != current_egress)
    if changed:
        return 'UNKNOWN', None, None, None
    scope = (before, egress) if before is not None and egress is not None and egress == current_egress else None
    return status, code, current_country if scope is not None else None, scope


def notify(service):
    if sys.platform != 'darwin':
        return False
    # Only a fixed catalog title enters AppleScript. No URL, network response or user text is interpolated.
    message = PROFILES[service][0] + ': service explicitly reports a regional restriction twice. Verify in the service.'
    script = 'on run argv\ndisplay notification (item 1 of argv) with title "TgWatch"\nend run'
    try:
        subprocess.run(['/usr/bin/osascript', '-e', script, message], timeout=5, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except (OSError, subprocess.SubprocessError):
        return False


def stop_requested(_signal, _frame):
    # Unwind bounded_probe's finally block so an app stop owns the child all the way through join.
    raise KeyboardInterrupt


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--service', action='append', choices=PROFILES,
                        help='Repeat to select profiles; default: all five')
    parser.add_argument('--watch', action='store_true', help='Run in foreground; Ctrl-C stops')
    parser.add_argument('--interval', type=int, default=300, help='300..3600 seconds in watch mode')
    parser.add_argument('--notify', action='store_true', help='Local macOS notifications after independent confirmation')
    output = parser.add_mutually_exclusive_group()
    output.add_argument('--json', action='store_true', help='Safe JSON lines, no response contents or IP addresses')
    output.add_argument('--app-json', action='store_true', help='Private versioned IPC for the native Mac app')
    args = parser.parse_args(argv)
    if not 300 <= args.interval <= 3600:
        parser.error('--interval must be between 300 and 3600 seconds')
    if args.app_json and args.notify:
        parser.error('--app-json notifications are managed by the native application')
    services = list(dict.fromkeys(args.service or PROFILES))
    states = {service: Consensus() for service in services}
    app = AppEvidence(services) if args.app_json else None
    due = {service: 0.0 for service in services}
    previous_term = signal.signal(signal.SIGTERM, stop_requested)
    try:
        while True:
            for service in services:
                now = time.monotonic()
                if args.watch and now < due[service]:
                    continue
                status, code, country, scope = measure(service)
                if app is not None:
                    context_id, state = app.update(service, status, scope, time.monotonic())
                else:
                    if scope is None:
                        states[service] = Consensus()
                    else:
                        states[service].update(status, scope, time.monotonic())
                    state = states[service]
                checked_at = int(time.time() * 1000)
                report = app_report(service, status, code, country, checked_at, context_id, state.confirmed) if (
                    app is not None) else safe_report(service, status, code, country, checked_at)
                if args.json or args.app_json:
                    print(json.dumps(report, ensure_ascii=False), flush=True)
                else:
                    print(f"{PROFILES[service][0]}: {status} · country={report['country'] or 'unverified'} · "
                          'public website only; account features unverified', flush=True)
                if args.notify and state.should_notify(time.monotonic()) and notify(service):
                    state.delivered(time.monotonic())
                completed = time.monotonic()
                delay = app.next_delay(service, completed, args.interval) if app is not None else (
                    15 if status == 'REGION_RESTRICTED' and not state.confirmed else args.interval)
                due[service] = completed + delay
            if not args.watch:
                return 0
            time.sleep(max(0.1, min(due.values()) - time.monotonic()))
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError, subprocess.SubprocessError):
        print('Regional check could not complete; no account or regional conclusion was made.', file=sys.stderr)
        return 1
    finally:
        signal.signal(signal.SIGTERM, previous_term)


if __name__ == '__main__':
    multiprocessing.freeze_support()
    sys.exit(main())
