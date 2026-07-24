meta:
  id: sftp_copy_data
  endian: be
  imports:
    - byte_string
doc: SSH_FXP_EXTENDED copy-data payload, excluding the request ID.
doc-ref: https://github.com/openssh/openssh-portable/blob/master/PROTOCOL
seq:
  - id: extension_name
    type: byte_string
  - id: src_handle
    type: byte_string
  - id: src_offset
    type: u8
  - id: length
    type: u8
  - id: dst_handle
    type: byte_string
  - id: dst_offset
    type: u8
