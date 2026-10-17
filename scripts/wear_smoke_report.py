"""Require successful execution of each real Wear device contract, with no assumption skips."""
import json
from pathlib import Path
import re
import sys

EXPECTED = {
    'cacheSurvivesReopenAndCannotReuseElapsedAcrossBoot',
    'standaloneLaunchIsPassiveAndManualCheckIsBounded',
    'tileAndComplicationNeverProbeOnRequestIncludingAmbient',
    'tapOpensOneManualCheckButForgedLaunchIsPassive',
}


def verify(text, api):
    if api < 30 or re.search(r'INSTRUMENTATION_STATUS_CODE: -|FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed', text):
        raise ValueError('Wear tests failed, skipped or ran on an unsupported device')
    totals = re.findall(r'^OK \(([0-9]+) tests?\)', text, re.M)
    successful = set(re.findall(r'^INSTRUMENTATION_STATUS: test=(\w+)\s*\nINSTRUMENTATION_STATUS_CODE: 0$', text, re.M))
    if len(totals) != 1 or int(totals[0]) < len(EXPECTED) or not EXPECTED <= successful:
        raise ValueError('Missing executed Wear manual/cache/surface contracts')
    return {'applicationId': 'ru.tgwatch.wear', 'api': api, 'executed': int(totals[0]),
            'failed': 0, 'skipped': 0, 'surfaceCallbacksExecuted': True}


if __name__ == '__main__':
    directory = Path(sys.argv[1])
    result = verify((directory / 'instrumentation.txt').read_text(), int(sys.argv[2]))
    (directory / 'result.json').write_text(json.dumps(result) + '\n')
