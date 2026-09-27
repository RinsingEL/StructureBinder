"""Explicit authored exterior standing level; never inferred from geometry."""

def resolve_ground_plane(author, size=None):
    if 'ground_plane' not in author:
        return dict(status='unmarked', y=None, note='', message='外部地面未标')
    plane = author['ground_plane']
    dimensions = size if size is not None else author.get('size', [])
    if not isinstance(plane, dict):
        return dict(status='invalid', y=None, note='', message='ground_plane must be an object')
    y, note = plane.get('y'), plane.get('note')
    if type(y) is not int or len(dimensions) != 3 or not 0 <= y < dimensions[1]:
        return dict(status='invalid', y=None, note='', message='ground_plane.y must be an integer within template height')
    if not isinstance(note, str) or not note.strip():
        return dict(status='invalid', y=None, note='', message='ground_plane.note must be a nonempty explanation')
    return dict(status='marked', y=y, note=note, message='作者已标外部地面')
