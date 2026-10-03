import json
import os

from kalku_python import __main__ as entry


def test_the_protocol_is_served_on_the_descriptors_it_is_given_until_shutdown():
    stdin_r, stdin_w = os.pipe()
    stdout_r, stdout_w = os.pipe()
    os.write(stdin_w, b'{"type":"shutdown","id":3}\n')

    code = entry.main(stdin_r, stdout_w)

    os.close(stdout_w)
    said = os.read(stdout_r, 4096)
    assert code == 0 and json.loads(said) == {"type": "bye", "id": 3}
    for fd in (stdin_r, stdin_w, stdout_r):
        os.close(fd)


def test_the_end_of_input_ends_the_conversation():
    stdin_r, stdin_w = os.pipe()
    stdout_r, stdout_w = os.pipe()
    os.close(stdin_w)

    assert entry.main(stdin_r, stdout_w) == 0

    os.close(stdout_w)
    assert os.read(stdout_r, 4096) == b""
    os.close(stdin_r)
    os.close(stdout_r)


def open_descriptors() -> int:
    return len(os.listdir("/dev/fd"))


def test_the_descriptor_the_protocol_keeps_is_closed_when_it_is_done():
    stdin_r, stdin_w = os.pipe()
    stdout_r, stdout_w = os.pipe()
    os.write(stdin_w, b'{"type":"shutdown","id":1}\n')
    before = open_descriptors()

    entry.main(stdin_r, stdout_w)

    assert open_descriptors() == before
    for fd in (stdin_r, stdin_w, stdout_r, stdout_w):
        os.close(fd)


def test_on_standard_output_the_protocol_keeps_its_own_descriptor_and_what_is_printed_goes_to_stderr():
    stdin_r, stdin_w = os.pipe()
    stdout_r, stdout_w = os.pipe()
    saved = os.dup(1)
    os.dup2(stdout_w, 1)
    try:
        os.write(stdin_w, b'{"type":"shutdown","id":2}\n')
        entry.main(stdin_r)
        # Whatever is written to descriptor 1 now is no longer the protocol.
        again = os.fstat(1)
        os.write(1, b"stray")
    finally:
        os.dup2(saved, 1)
        os.close(saved)
    os.close(stdout_w)
    said = os.read(stdout_r, 4096)

    assert json.loads(said) == {"type": "bye", "id": 2}
    assert b"stray" not in said
    assert again is not None
    for fd in (stdin_r, stdin_w, stdout_r):
        os.close(fd)
