"""Explicit phone/watch publication identities; no inferred or arbitrary modules."""
APPS = {
    'ru.tgwatch': {'module': 'app', 'prefix': 'TgWatch', 'name': 'TgWatch', 'min_sdk': 26},
    'ru.tgwatch.wear': {'module': 'wear', 'prefix': 'TgWatch-wear', 'name': 'TgWatch Wear', 'min_sdk': 30},
}
SUFFIXES = ('.apk', '.apk.sha256', '.dependencies.json', '.osv.json', '.sbom.cdx.json', '.trivy.json')


def application(application_id):
    if application_id not in APPS:
        raise ValueError('Unsupported application identity')
    return APPS[application_id]


def release_assets():
    return {app['prefix'] + suffix for app in APPS.values() for suffix in SUFFIXES}


def sbom_path(application_id):
    app = application(application_id)
    return f"{app['module']}/build/reports/sbom/{app['prefix']}.sbom.cdx.json"


def verify_application_evidence(inventory, bom=None, report=None, application_id=None):
    actual = inventory.get('applicationId', 'ru.tgwatch')
    app = application(actual)
    if application_id is not None and actual != application_id:
        raise ValueError('Resolved inventory belongs to a different application')
    if bom is not None:
        component = bom.get('metadata', {}).get('component', {})
        if component.get('bom-ref') != actual + ':releaseRuntimeClasspath' or component.get('name') != app['name']:
            raise ValueError('SBOM root belongs to a different application')
        if inventory.get('applicationVersion') is not None and component.get('version') != inventory['applicationVersion']:
            raise ValueError('SBOM version differs from resolved inventory')
    if report is not None and ('applicationId' in inventory or actual != 'ru.tgwatch'):
        if report.get('applicationId') != actual or report.get('applicationVersion') != inventory.get('applicationVersion'):
            raise ValueError('OSV evidence belongs to a different application/version')
    return actual
