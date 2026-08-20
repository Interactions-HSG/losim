# Scenarios

A scenario is data. Anything that needs computation points at a Java class —
that line is deliberate, and it is what keeps this from growing into a language
with its own type checker.

No arithmetic in strings, no conditionals, no loops. Repetition is `count:`.

## Machines

Three forms, so a fleet can be uniform, heterogeneous, or both:

```yaml
vms:
  master:                          # individually named
    program: Master
    instance: m5.large
    availability_zone: eu-central-1a

  workers:                         # a pool -> w0 .. w5
    prefix: w
    programs: [Mapper, Reducer]    # several programs on one machine
    instance: m5.large
    count: 6
    market: spot                   # ~70% cheaper, reclaimable on notice
    availability_zone: [eu-central-1a, eu-central-1b]
    overrides:                     # uniform *except*
      w3: {instance: t3.micro}
```

Resource knobs are **derived** from the instance type, never declared: a reducer
OOMs because you picked a `t3.micro` with 1 GiB, a straggler is a `t3` out of
burst credits, a crash is a spot reclaim.

| Family | For |
|---|---|
| `t3.*` | burstable — the straggler generator |
| `m5.*` | balanced |
| `c5.*` | compute |
| `r5.*` | memory |
| `i3.*` | local storage |

## Locality

Three tiers, charged differently — which is how "move the computation to the
data" stops being a slogan:

| A read from | Latency | Egress |
|---|---|---|
| the same VM | ~0, loopback | free |
| the same availability zone | intra-AZ | free |
| another zone | inter-AZ | **billed per GB** |

## Failure

```yaml
faults:
  - {at: 2s,  degrade: w3, cpu: 0.1}          # ten times slower
  - {at: 5s,  kill: w0}                        # fail-stop
  - {at: 5s,  kill: w0, restart_after: 2s}     # back, with empty memory
  - {at: 6s,  freeze: w2, for: 3s}             # answers late; not dead
  - {at: 9s,  spot_reclaim: w1, notice: 2s}    # @OnTerminate fires first
  - {at: 10s, exhaust_credits: w3}             # a t3 runs out of burst
  - {at: 12s, partition: [[w0, w1], [w2, w3]]}
  - {at: 15s, heal: all}
```

## Everything else

```yaml
name: wordcount
seed: 42                 # same seed, same run, byte for byte
run_until: 30s
codec: proto             # or: record (schema-less, and larger)
prices: ../prices/eu-central-1.yaml
input: >
  the cat sat on the mat

network:
  topology: mesh         # or ring
  latency: {mean: 20ms, stddev: 5ms}
  loss: 0.0

invariants:
  - {name: no lost words, check: NoLostWords}

sweep:                   # explore the space without writing code
  seed: [1..1000]
  matrix:
    workers.instance: [t3.micro, m5.large, c5.xlarge]
    workers.count:    [2, 4, 8]
    workers.programs: [Mapper+Reducer, Mapper]    # combiner on / off
```

`losim check scenario.yaml` validates it and reports `file:line`, never a stack
trace.
