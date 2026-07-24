meta:
  id: sftp_version
  endian: be
  imports:
    - byte_string
doc: SSH_FXP_VERSION payload, including advertised extension pairs.
doc-ref: https://datatracker.ietf.org/doc/html/draft-ietf-secsh-filexfer-02#section-3
seq:
  - id: version
    type: u4
  - id: extensions
    type: extension
    repeat: eos
types:
  extension:
    seq:
      - id: name
        type: byte_string
      - id: data
        type: byte_string
