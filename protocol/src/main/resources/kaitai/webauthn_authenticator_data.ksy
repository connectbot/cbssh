meta:
  id: webauthn_authenticator_data
  endian: be
seq:
- id: rp_id_hash
  size: 32
- id: flags
  type: u1
  doc: Assertion flags; AT (0x40, attested credential data) must be clear.
  valid:
    expr: (_ & 0x40) == 0
- id: counter
  type: u4
- id: extensions
  size-eos: true
  doc: Extension bytes must be present exactly when ED (0x80) is set.
  valid:
    expr: ((flags & 0x80) != 0) == (_.size > 0)
