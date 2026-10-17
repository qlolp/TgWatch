"""The sole reviewed first-party dependency; never treat an arbitrary project/file as Maven."""
import re


def is_local_core(record, application_version):
    kind = record.get('kind', 'maven')
    if kind == 'maven':
        if 'projectPath' in record or 'buildPath' in record:
            raise ValueError('Maven dependency cannot impersonate a local project')
        return False
    if kind != 'project':
        raise ValueError('Unsupported non-Maven dependency kind')
    if not isinstance(application_version, str) or not re.fullmatch(
            r'(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})', application_version):
        raise ValueError('Local core requires the canonical application source version')
    expected = {'group': 'ru.tgwatch', 'name': 'probe-core', 'version': application_version,
                'projectPath': ':probe-core', 'buildPath': ':'}
    if any(record.get(key) != value for key, value in expected.items()):
        raise ValueError('Only the current build :probe-core at the application version is supported')
    return True


def core_ref(version):
    return 'project::probe-core:' + version


def core_purl(version):
    return 'pkg:generic/ru.tgwatch/probe-core@' + version + '?type=jar'
