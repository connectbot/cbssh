meta:
  id: unencrypted_packet
  endian: be
  imports:
  - invalid_message
  - ssh_enums
  - ssh_msg_channel_data
  - ssh_msg_channel_extended_data
  - ssh_msg_debug
  - ssh_msg_disconnect
  - ssh_msg_ext_info
  - ssh_msg_ignore
  - ssh_msg_kexinit
  - ssh_msg_newcompress
  - ssh_msg_service_accept
  - ssh_msg_service_request
  - ssh_msg_unimplemented
seq:
- id: len_packet
  type: u4
  valid:
    expr: _ >= 6 and _ == _io.size - _io.pos
- id: len_random_padding
  type: u1
  valid:
    expr: _ >= 4 and _ <= len_packet - 2
- id: payload
  type: unencrypted_payload(false)
  size: len_packet - len_random_padding - 1
- id: random_padding
  size: len_random_padding
types:
  unencrypted_payload:
    params:
    - id: decode_channel_data
      type: bool
    seq:
    - id: message_type
      type: u1
      enum: ssh_enums::message_type
    - id: body
      if: not (decode_channel_data and (message_type == ssh_enums::message_type::ssh_msg_channel_data or message_type == ssh_enums::message_type::ssh_msg_channel_extended_data))
      size: _io.size - 1
      type:
        switch-on: message_type
        cases:
          ssh_enums::message_type::ssh_msg_disconnect: ssh_msg_disconnect
          ssh_enums::message_type::ssh_msg_ignore: ssh_msg_ignore
          ssh_enums::message_type::ssh_msg_unimplemented: ssh_msg_unimplemented
          ssh_enums::message_type::ssh_msg_service_request: ssh_msg_service_request
          ssh_enums::message_type::ssh_msg_service_accept: ssh_msg_service_accept
          ssh_enums::message_type::ssh_msg_debug: ssh_msg_debug
          ssh_enums::message_type::ssh_msg_ext_info: ssh_msg_ext_info
          ssh_enums::message_type::ssh_msg_newcompress: ssh_msg_newcompress
          ssh_enums::message_type::ssh_msg_kexinit: ssh_msg_kexinit
          _: invalid_message
    - id: channel_data
      type: ssh_msg_channel_data
      if: decode_channel_data and message_type == ssh_enums::message_type::ssh_msg_channel_data
    - id: channel_extended_data
      type: ssh_msg_channel_extended_data
      if: decode_channel_data and message_type == ssh_enums::message_type::ssh_msg_channel_extended_data
    instances:
      message_number:
        pos: 0
        type: u1
