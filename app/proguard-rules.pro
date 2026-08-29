# Nothing here yet, and that is a finding rather than an omission.
#
# delivery-gate.md 6.3: the interesting half of R8's report is what it KEPT, because anything
# held alive only by a -keep rule with no remaining reason is dead code carrying a note from you
# saying spare this. This app has no reflection, no serialisation, no dynamic class loading and
# no entry point that is not in the manifest, so it needs no rules of its own. If a rule is ever
# added here, the reason goes above it.
