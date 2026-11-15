#!/usr/bin/env python3
"""Build-time implementation of the framework's version-1 local overlay format.

The same overlay.json and assets are consumed by ProjectLocalOverlay on Android.
"""
import hashlib
import json
from pathlib import Path
import re
import shutil


def safe_file(root, name):
    if not name or name.startswith('/') or '\\' in name or ':' in name or any(p in ('', '.', '..') for p in name.split('/')):
        raise ValueError(f'Unsafe payload path: {name}')
    if name.split('/')[0] in ('config', 'logs', 'debug', 'diagnostics'):
        raise ValueError(f'Payload must not contain user data: {name}')
    path = root / name
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError(f'Payload path escapes root: {name}')
    return path


def fingerprint(overlay):
    raw = (overlay / 'overlay.json').read_bytes()
    manifest = json.loads(raw)
    digest = hashlib.sha256(raw)
    for name in sorted({op['asset'] for op in manifest['operations'] if 'asset' in op}):
        digest.update(name.encode())
        digest.update(safe_file(overlay, name).read_bytes())
    return digest.hexdigest()


def replace_exactly(text, op):
    old, new = op['old'], op['new']
    if not old or not new or old == new:
        raise ValueError('Invalid text replacement')
    old_count, new_count = text.replace(new, '').count(old), text.count(new)
    if old_count + new_count != 1:
        raise ValueError(f'Upstream text changed; local overlay needs review: {old}')
    return text if new_count else text.replace(old, new)


def merge(old, patch, new_keys_only=False):
    result = dict(old)
    for key, value in patch.items():
        previous = result.get(key)
        if new_keys_only and previous is not None and previous != value:
            raise ValueError(f'Upstream now defines local node: {key}')
        result[key] = merge(previous, value) if not new_keys_only and isinstance(previous, dict) and isinstance(value, dict) else value
    return result


def edit(node, path, transform):
    if not path:
        return transform(node)
    head, *tail = path
    if head.startswith('@'):
        key, value = head[1:].split('=', 1)
        if sum(item.get(key) == value for item in node) != 1:
            raise ValueError(f'Overlay selector no longer matches: {head}')
        return [edit(item, tail, transform) if item.get(key) == value else item for item in node]
    result = dict(node)
    result[head] = edit(node[head], tail, transform) if tail else transform(node.get(head))
    return result


def apply_overlay(root, overlay):
    root, overlay = Path(root), Path(overlay)
    manifest = json.loads((overlay / 'overlay.json').read_text())
    if manifest['version'] != 1:
        raise ValueError('Unsupported local overlay version')
    for op in manifest['operations']:
        kind = op['op']
        if kind == 'json_nodes':
            matched_nodes = 0
            for file in root.rglob('*.json'):
                if not re.fullmatch(op['files'], file.relative_to(root).as_posix()):
                    continue
                before = json.loads(file.read_text(encoding='utf-8-sig'))
                matched_nodes += len(before.keys() & op['value'].keys())
                after = {name: merge(node, op['value'][name]) if name in op['value'] else node for name, node in before.items()}
                if before != after:
                    file.write_text(json.dumps(after, ensure_ascii=False, indent=2) + '\n')
            if not matched_nodes:
                raise ValueError(f'Overlay node selector matched nothing: {op["files"]}')
            continue
        file = safe_file(root, op['file'])
        file.parent.mkdir(parents=True, exist_ok=True)
        if kind == 'copy':
            shutil.copyfile(safe_file(overlay, op['asset']), file)
        elif kind == 'copy_project':
            shutil.copyfile(safe_file(root, op['source']), file)
        elif kind == 'text':
            result = replace_exactly(file.read_text(), op)
            if file.suffix == '.py':
                compile(result, op['file'], 'exec')
            file.write_text(result)
        elif kind in ('json_set', 'json_text', 'json_merge'):
            before = json.loads(file.read_text(encoding='utf-8-sig')) if file.exists() else {}
            def transform(old):
                if kind == 'json_text':
                    return replace_exactly(old, op)
                if kind == 'json_merge':
                    return merge(old or {}, op['value'], op.get('new_keys_only', False))
                if 'expected' in op and old != op['expected'] and old != op['value']:
                    raise ValueError(f'Upstream JSON changed: {op["file"]} {op["path"]}')
                return op['value']
            after = edit(before, op.get('path', []), transform)
            file.write_text(json.dumps(after, ensure_ascii=False, indent=2) + '\n')
        else:
            raise ValueError(f'Unknown overlay operation: {kind}')
