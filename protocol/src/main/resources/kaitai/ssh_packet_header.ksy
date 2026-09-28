meta:
  id: ssh_packet_header
  endian: be
doc: Validate decrypted packet padding before exposing a bounded payload substream.
seq:
- id: len_random_padding
  type: u1
  valid:
    expr: _ >= 4 and _ <= _io.size - 2
instances:
  payload_length:
    value: _io.size - len_random_padding - 1
