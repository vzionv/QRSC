#!/usr/bin/env python3
"""SCQR protocol unit tests (no GUI)."""
import os, sys, tempfile
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from qrsc_file_qt import (
    qr_bytes, make_chunk_header, parse_chunk_header,
    chunk_file, is_cache_file, cache_path_for
)

def test_header_roundtrip():
    h = make_chunk_header(42, 7, 'test_file.bin', True)
    ph = parse_chunk_header(h)
    assert ph['total'] == 42
    assert ph['index'] == 7
    assert ph['filename'] == 'test_file.bin'
    assert ph['is_last'] == True
    assert ph['version'] == 1
    assert len(h) == 11 + len('test_file.bin'.encode())
    print(f'  OK: header {len(h)}B, total={ph["total"]}, index={ph["index"]}')

def test_non_header_returns_none():
    assert parse_chunk_header(b'hello world') is None
    assert parse_chunk_header(b'') is None
    assert parse_chunk_header(b'SCQ') is None
    print('  OK: non-header detection')

def test_multi_chunk_roundtrip():
    with tempfile.NamedTemporaryFile(delete=False, suffix='.bin') as f:
        original = os.urandom(10000)
        f.write(original)
        tmppath = f.name
    try:
        chunks, fname, total = chunk_file(tmppath)
        print(f'  File: 10000B -> {total} chunks')
        reassembled = b''
        for i, c in enumerate(chunks):
            ph = parse_chunk_header(c)
            assert ph['index'] == i
            assert ph['total'] == total
            reassembled += ph['payload']
        assert reassembled == original
        print('  OK: reassembly exact match')
    finally:
        os.unlink(tmppath)

def test_single_chunk():
    with tempfile.NamedTemporaryFile(delete=False, suffix='.txt') as f:
        f.write(b'hello')
        spath = f.name
    try:
        chunks, fn, total = chunk_file(spath)
        assert total == 1
        ph = parse_chunk_header(chunks[0])
        assert ph['payload'] == b'hello'
        assert ph['is_last'] == True
        print('  OK: single chunk')
    finally:
        os.unlink(spath)

def test_qr_bytes_encoding():
    test_data = bytes(range(256)) * 10  # 2560 bytes
    mat, ver, count = qr_bytes(test_data)
    assert len(mat) == 4 * ver + 17
    assert count == len(test_data)
    print(f'  OK: v{ver} {len(mat)}x{len(mat)} matrix, {count}B')

def test_qr_bytes_binary():
    """Test that all byte values 0x00-0xFF survive encoding."""
    test_data = bytes(range(256))
    mat, ver, count = qr_bytes(test_data)
    assert count == 256
    print(f'  OK: all 256 byte values, v{ver}')

def test_cache_naming():
    cp = cache_path_for('my.document.pdf', '1430', 0)
    assert '__my_document_pdf_1430_00000.qrf' in cp
    assert is_cache_file(cp)
    assert not is_cache_file('normal_file.txt')
    print('  OK: cache naming')

def test_empty_file():
    with tempfile.NamedTemporaryFile(delete=False, suffix='.empty') as f:
        epath = f.name
    try:
        chunks, fn, total = chunk_file(epath)
        assert total == 0
        assert len(chunks) == 0
        print('  OK: empty file -> 0 chunks')
    finally:
        os.unlink(epath)

def test_large_file_chunk_count():
    with tempfile.NamedTemporaryFile(delete=False, suffix='.bin') as f:
        original = os.urandom(50000)
        f.write(original)
        tmppath = f.name
    try:
        chunks, fn, total = chunk_file(tmppath)
        actual_chunk_size = CHUNK_SIZE - (11 + len(os.path.basename(tmppath).encode()))
        expected = (50000 + actual_chunk_size - 1) // actual_chunk_size
        assert total == len(chunks)
        reassembled = b''.join(parse_chunk_header(c)['payload'] for c in chunks)
        assert reassembled == original
        print(f'  OK: 50000B -> {total} chunks (expected ~{expected})')
    finally:
        os.unlink(tmppath)


if __name__ == '__main__':
    from qrsc_file_qt import CHUNK_SIZE
    tests = [
        test_header_roundtrip,
        test_non_header_returns_none,
        test_single_chunk,
        test_multi_chunk_roundtrip,
        test_qr_bytes_encoding,
        test_qr_bytes_binary,
        test_cache_naming,
        test_empty_file,
        test_large_file_chunk_count,
    ]
    failed = 0
    for t in tests:
        name = t.__name__.replace('_', ' ').title()
        print(f'[{name}]')
        try:
            t()
        except Exception as e:
            print(f'  FAIL: {e}')
            failed += 1
        print()
    print(f'Results: {len(tests)-failed}/{len(tests)} passed')
    if failed:
        print(f'{failed} FAILURES')
        sys.exit(1)
