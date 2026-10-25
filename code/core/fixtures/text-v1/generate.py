#!/usr/bin/env python3
"""Independent SYNO text-vector encoder; no Rust-generated expectations."""
import json
import pathlib
import struct

note, author, op, mark, sheet, base = [
    '01ARZ3NDEKTSV4RRFFQ69G5FA' + c for c in 'VWXYZ0'
]

def string(s):
    data = s.encode('utf-8')
    return struct.pack('<I', len(data)) + data

head = b'SYNO\x01\x00\x01' + b''.join(map(string, [note, author, op]))
head += struct.pack('<I', 1) + string(base) + b'\x00' + struct.pack('<I', 1)
entity = string(mark) + b'\x01' + string(base)
style = {'size': 'Large', 'colour': 'Blue'}
add = dict(mark_id=mark, sheet_id=sheet, role='Document', origin_x=-0.0,
           origin_y=12.5, width=640.0, text='Aé🙂\n', style=style)
cases = [
    ('AddText', add, b'\x05' + string(mark) + string(sheet) + b'\x00'
     + struct.pack('<fff', -0.0, 12.5, 640.0) + string(add['text']) + b'\x02\x03'),
    ('SpliceText', dict(offset=1, delete=2, insert='ñ'),
     b'\x06' + entity + struct.pack('<II', 1, 2) + string('ñ')),
    ('SetTextWidth', dict(width=720.5), b'\x07' + entity + struct.pack('<f', 720.5)),
    ('DeleteText', {}, b'\x08' + entity),
    ('RestoreText', {}, b'\x09' + entity),
    ('TransformText', dict(transform=dict(a=1.0, b=0.0, c=0.0, d=2.0, tx=-0.0, ty=30.0)),
     b'\x0a' + entity + struct.pack('<6d', 1, 0, 0, 2, -0.0, 30)),
    ('RestyleText', dict(style=style), b'\x0b' + entity + b'\x02\x03'),
]
vectors = []
for name, fields, encoded in cases:
    if name != 'AddText':
        fields = dict(mark_id=mark, base={'Operation': {'operation_id': base}}, **fields)
    vectors.append(dict(name=name, note_id=note, author_id=author,
                        command=dict(operation_id=op, dependencies=[base, base],
                                     body={'Marks': {'mutations': [{name: fields}]}}),
                        canonical_hex=(head + encoded).hex(), canonical_length=len(head + encoded)))
pathlib.Path(__file__).with_name('canonical.json').write_text(
    json.dumps(vectors, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
