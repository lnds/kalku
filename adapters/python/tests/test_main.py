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
