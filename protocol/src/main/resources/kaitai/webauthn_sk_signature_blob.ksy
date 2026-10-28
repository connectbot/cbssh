meta:
  id: webauthn_sk_signature_blob
  endian: be
  imports:
  - ecdsa_signature_blob
  - byte_string
seq:
- id: signature
  type: ecdsa_signature_blob
- id: flags
  type: u1
  doc: Assertion flags; AT (0x40, attested credential data) must be clear.
  valid:
    expr: (_ & 0x40) == 0
- id: counter
  type: u4
- id: origin
  type: byte_string
- id: client_data
  type: byte_string
- id: extensions
  type: byte_string
  doc: Extension bytes must be present exactly when ED (0x80) is set.
  valid:
    expr: ((flags & 0x80) != 0) == (_.data.size > 0)
