meta:
  id: ssh_certificate
  endian: be
  imports:
    - ascii_string
    - byte_string
    - ssh_rsa_public_key_blob
    - ssh_ed25519_public_key_blob
    - ecdsa_public_key_blob
    - sk_ed25519_public_key_blob
    - sk_ecdsa_p256_public_key_blob
doc-ref: https://www.openssh.org/specs.html
seq:
  - id: algorithm_name
    type: ascii_string
  - id: nonce
    type: byte_string
  - id: key
    type:
      switch-on: algorithm_name.value
      cases:
        '"ssh-rsa-cert-v01@openssh.com"': ssh_rsa_public_key_blob
        '"ssh-ed25519-cert-v01@openssh.com"': ssh_ed25519_public_key_blob
        '"ecdsa-sha2-nistp256-cert-v01@openssh.com"': ecdsa_public_key_blob
        '"ecdsa-sha2-nistp384-cert-v01@openssh.com"': ecdsa_public_key_blob
        '"ecdsa-sha2-nistp521-cert-v01@openssh.com"': ecdsa_public_key_blob
        '"sk-ssh-ed25519-cert-v01@openssh.com"': sk_ed25519_public_key_blob
        '"sk-ecdsa-sha2-nistp256-cert-v01@openssh.com"': sk_ecdsa_p256_public_key_blob
  - id: serial
    type: u8
  - id: certificate_type
    type: u4
    valid:
      any-of: [1, 2]
  - id: key_id
    type: byte_string
  - id: len_principals
    type: u4
    valid:
      expr: _ <= _io.size - _io.pos
  - id: principals
    type: string_list
    size: len_principals
  - id: valid_after
    type: u8
  - id: valid_before
    type: u8
  - id: len_critical_options
    type: u4
    valid:
      expr: _ <= _io.size - _io.pos
  - id: critical_options
    type: options
    size: len_critical_options
  - id: len_extensions
    type: u4
    valid:
      expr: _ <= _io.size - _io.pos
  - id: extensions
    type: options
    size: len_extensions
  - id: reserved
    type: byte_string
  - id: signature_key
    type: byte_string
  - id: signature
    type: byte_string
types:
  string_list:
    seq:
      - id: values
        type: byte_string
        repeat: eos
  options:
    seq:
      - id: entries
        type: option
        repeat: eos
  option:
    seq:
      - id: name
        type: ascii_string
      - id: data
        type: byte_string
