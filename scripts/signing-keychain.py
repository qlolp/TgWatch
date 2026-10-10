#!/usr/bin/env python3
"""Keep the existing PKCS12 password in macOS Keychain, never in command arguments."""
import argparse
import ctypes as C
import getpass
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


class Keychain:
    def __init__(self):
        if sys.platform != 'darwin':
            raise RuntimeError('Password protection requires macOS login Keychain')
        self.cf = C.CDLL('/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation')
        self.sec = C.CDLL('/System/Library/Frameworks/Security.framework/Security')
        for name, args, result in [
            ('CFStringCreateWithCString', [C.c_void_p, C.c_char_p, C.c_uint32], C.c_void_p),
            ('CFDataCreate', [C.c_void_p, C.c_char_p, C.c_long], C.c_void_p),
            ('CFDataGetLength', [C.c_void_p], C.c_long),
            ('CFDataGetBytePtr', [C.c_void_p], C.c_void_p),
            ('CFDictionaryCreateMutable', [C.c_void_p, C.c_long, C.c_void_p, C.c_void_p], C.c_void_p),
            ('CFDictionarySetValue', [C.c_void_p, C.c_void_p, C.c_void_p], None),
            ('CFRelease', [C.c_void_p], None)]:
            fn = getattr(self.cf, name); fn.argtypes = args; fn.restype = result
        for name, args in [('SecItemAdd', [C.c_void_p, C.c_void_p]),
                           ('SecItemCopyMatching', [C.c_void_p, C.POINTER(C.c_void_p)]),
                           ('SecItemDelete', [C.c_void_p])]:
            fn = getattr(self.sec, name); fn.argtypes = args; fn.restype = C.c_int32

    def constant(self, name):
        return C.c_void_p.in_dll(self.sec, name).value

    def dictionary(self, account, password=None, read=False):
        keys = C.addressof((C.c_char * 1).in_dll(self.cf, 'kCFTypeDictionaryKeyCallBacks'))
        values = C.addressof((C.c_char * 1).in_dll(self.cf, 'kCFTypeDictionaryValueCallBacks'))
        result = self.cf.CFDictionaryCreateMutable(None, 0, keys, values)
        def put(key, value): self.cf.CFDictionarySetValue(result, self.constant(key), value)
        def string(key, value):
            item = self.cf.CFStringCreateWithCString(None, value.encode(), 0x08000100)
            try: put(key, item)
            finally: self.cf.CFRelease(item)
        put('kSecClass', self.constant('kSecClassGenericPassword'))
        string('kSecAttrService', 'org.tgwatch.release-signing')
        string('kSecAttrAccount', account)
        # Fail explicitly on a locked/denied Keychain; never remove the old backup on failure.
        put('kSecUseAuthenticationUI', self.constant('kSecUseAuthenticationUIFail'))
        if read:
            put('kSecReturnData', C.c_void_p.in_dll(self.cf, 'kCFBooleanTrue').value)
        if password is not None:
            encoded = password.encode()
            item = self.cf.CFDataCreate(None, encoded, len(encoded))
            try: put('kSecValueData', item)
            finally: self.cf.CFRelease(item)
        return result

    def load(self, account):
        query = self.dictionary(account, read=True)
        result = C.c_void_p()
        try:
            status = self.sec.SecItemCopyMatching(query, C.byref(result))
            if status: raise RuntimeError(f'Keychain read failed ({status})')
            return C.string_at(self.cf.CFDataGetBytePtr(result), self.cf.CFDataGetLength(result)).decode()
        finally:
            if result.value: self.cf.CFRelease(result)
            self.cf.CFRelease(query)

    def save(self, account, password):
        query = self.dictionary(account, password=password)
        try:
            status = self.sec.SecItemAdd(query, None)
            if status == -25299:
                if self.load(account) != password: raise RuntimeError('Existing Keychain password differs; refusing overwrite')
            elif status: raise RuntimeError(f'Keychain write failed ({status})')
        finally: self.cf.CFRelease(query)
        if self.load(account) != password: raise RuntimeError('Keychain readback mismatch')

    def delete_fixture(self, account):
        query = self.dictionary(account)
        try:
            status = self.sec.SecItemDelete(query)
            if status not in (0, -25300): raise RuntimeError(f'Fixture cleanup failed ({status})')
        finally: self.cf.CFRelease(query)


def certificate(store, password):
    executable = Path(os.environ['JAVA_HOME']) / 'bin/keytool' if os.environ.get('JAVA_HOME') else Path(shutil.which('keytool') or 'keytool')
    env = {**os.environ, 'TGWATCH_SIGNING_PASSWORD': password}
    output = subprocess.run([str(executable), '-list', '-v', '-keystore', str(store),
        '-storepass:env', 'TGWATCH_SIGNING_PASSWORD', '-alias', 'tgwatch'],
        env=env, capture_output=True, text=True, check=True).stdout
    match = re.search(r'SHA256:\s*([0-9A-Fa-f:]+)', output)
    if not match: raise RuntimeError('Signing certificate not found')
    return match.group(1).replace(':', '').upper()


def protect(store, keychain, expected=None, verify=None):
    sidecar = store.with_name('tgwatch-signing-password.txt')
    password = sidecar.read_text().strip()
    before = certificate(store, password)
    if expected and before != expected.upper():
        raise RuntimeError('Certificate does not match the published signing identity')
    keychain.save(str(store.resolve()), password)
    if verify is None:
        subprocess.run([sys.executable, __file__, 'verify', str(store.parent), '--expected-certificate', before], check=True)
    else:
        verify(before)
    sidecar.unlink()
    print('Plaintext password sidecar removed; encrypted PKCS12 and verified Keychain item retained.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['protect', 'verify', 'configure'])
    parser.add_argument('directory', type=Path)
    parser.add_argument('--expected-certificate')
    args = parser.parse_args()
    store = args.directory.resolve() / 'tgwatch-release.p12'
    account = str(store)
    keychain = Keychain()
    if args.command == 'protect':
        protect(store,keychain,args.expected_certificate)
    elif args.command == 'verify':
        fingerprint = certificate(store, keychain.load(account))
        if args.expected_certificate and fingerprint != args.expected_certificate.upper():
            raise RuntimeError('Recovered signing identity mismatch')
        print(f'Signing certificate SHA256: {fingerprint}')
    else:
        if not store.is_file():
            raise RuntimeError('Restore the permanent keystore first. This command never generates a replacement key.')
        try: password = keychain.load(account)
        except RuntimeError:
            password = getpass.getpass('Existing keystore password: ')
            certificate(store, password)
            keychain.save(account, password)
        fingerprint = certificate(store, password)
        import base64
        secrets = {'TGWATCH_KEYSTORE_BASE64': base64.b64encode(store.read_bytes()),
            'TGWATCH_KEYSTORE_PASSWORD': password.encode(), 'TGWATCH_KEY_PASSWORD': password.encode(),
            'TGWATCH_KEY_ALIAS': b'tgwatch'}
        for name, value in secrets.items():
            subprocess.run(['gh', 'secret', 'set', name, '--repo', 'qlolp/TgWatch'], input=value, check=True)
        print(f'Signing secrets configured with existing certificate {fingerprint}.')


if __name__ == '__main__':
    try: main()
    except Exception as error:
        # Do not emit child process environments, stdin, passwords or a traceback.
        print(f'Signing operation failed: {type(error).__name__}: {error}', file=sys.stderr)
        raise SystemExit(1)
