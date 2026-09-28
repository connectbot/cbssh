meta:
  id: sftp_frame_header
  endian: be
seq:
- id: length
  type: u4
  valid:
    min: 1
    max: 262144
types:
  body_header:
    seq:
    - id: packet_type
      type: u1
  response_header:
    seq:
    - id: request_id
      type: u4
