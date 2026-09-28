meta:
  id: ssh_msg_channel_data
  endian: be
  imports:
  - byte_string
doc-ref: RFC 4254 section 5.2
seq:
- id: recipient_channel
  type: u4
- id: data
  type: byte_string
types:
  header:
    doc: >
      The fixed channel-data prefix, for serializing a source array range directly
      into an owned packet buffer without an intermediate chunk array.
    seq:
    - id: recipient_channel
      type: u4
    - id: data_length
      type: u4
