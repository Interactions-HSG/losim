# Prices

Course data, deliberately outside the simulator. One file per region; pick one
with `--prices`:

```bash
java -cp "$CP" losim.cli.Main bill build/runs/mine.json --prices prices/ap-northeast-1.yaml
```

Nothing here changes what a run *does*. Billing is a pure function of the trace,
so the same run can be priced in ten places without being run again — which is
the point: *would this design still be sensible in Tokyo?* is a question you
answer by re-billing, not by re-running.

## The ten regions

| file | provider | where | index | build / service / month |
|---|---|---|---|---|
| `us-east-1` | AWS | N. Virginia | 0.85 | 280 |
| `us-west-2` | AWS | Oregon | 0.88 | 300 |
| `eu-west-1` | AWS | Ireland | 0.95 | 240 |
| `eu-central-1` | AWS | Frankfurt | 1.00 | 250 |
| `ap-south-1` | AWS | Mumbai | 1.10 | 90 |
| `switzerlandnorth` | Azure | Zurich | 1.15 | 330 |
| `ap-northeast-1` | AWS | Tokyo | 1.25 | 260 |
| `australiaeast` | Azure | Sydney | 1.30 | 290 |
| `southafricanorth` | Azure | Johannesburg | 1.35 | 130 |
| `sa-east-1` | AWS | São Paulo | 1.45 | 120 |

Ten rather than every region either cloud sells. A list of seventy-six is a list
nobody reads; what a course needs is enough places, far enough apart, that "the
next rack", "somewhere else in Europe" and "the other side of the world" are
visibly three different prices.

## These are estimates

**No figure here is a vendor's list price, and none should be quoted as one.**
Egress and storage are the Frankfurt baseline times the region's index, which is
a rough ordering of what infrastructure costs where. `build_per_service_month`
follows what an engineer costs locally instead, which is why Mumbai is cheap to
build in and expensive to leave, and Zurich is the other way round.

They are wrong in the third decimal on purpose. A number that looked
authoritative would be argued with instead of reasoned from, and the thing worth
reasoning about is the *ratio* — that an intercontinental gigabyte costs nine
times an in-region one, everywhere, and that no amount of tuning changes which
side of that a design's traffic lands on.

Real prices move quarterly, differ per contract, and are not what this course is
teaching. If you want the real ones, put them in these files: that is what the
files are for, and nothing in `losim/` has to change.

## Talking costs what the distance costs

Three rates, because there are three distances a byte can travel — and one that
is free:

| link | rate | when |
|---|---|---|
| same zone | free | both machines in `eu-central-1a` |
| same region | `egress_per_gb` | `eu-central-1a` -> `eu-central-1b` |
| same continent | `egress_cross_region_per_gb` | `eu-central-1a` -> `eu-west-1a` |
| across an ocean | `egress_intercontinental_per_gb` | `eu-central-1a` -> `ap-northeast-1a` |

A machine's zone decides which. The region is read off the zone name — `eu-central-1a`
is in `eu-central-1`, `switzerlandnorth-1` is in `switzerlandnorth` — so there is
nothing to declare and nothing to keep in step.

The trace records the split (`egressMb`, per machine, by destination region) as
the calls happen, because only the caller knows both ends. The bill then prints
up to three egress lines rather than one, so traffic that crossed an ocean is
a row you can see rather than a number folded into a total.

A zone losim does not recognise — `rack-3`, `left` — is its own region on an
unknown continent, and traffic to it is billed at the **cross-region** rate. The
cheaper of the two, deliberately: guessing "across an ocean" for a name nobody
recognised would put the largest egress line on the bill on the strength of a
guess.

## Every field

See [the manual](../docs/ref/prices.mdx).
