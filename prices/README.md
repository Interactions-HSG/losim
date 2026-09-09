# Prices

These files hold course billing data outside the simulator. Choose a region with
`--prices`:

```bash
java -cp "$CP" losim.cli.Main bill build/runs/mine.json --prices prices/ap-northeast-1.yaml
```

Billing is a pure function of the trace, so changing the region does not rerun the
simulation. Re-bill the same trace to compare the design under another region's
prices.

## Regions

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

The table covers a selection of regions rather than every region offered by either
provider. The locations provide distinct same-region, cross-region, and
intercontinental prices for course examples.

## Estimate policy

**These figures are not vendor list prices.**
Egress and storage are the Frankfurt baseline times the region's index, which is
a rough ordering of what infrastructure costs where. `build_per_service_month`
follows what an engineer costs locally instead, which is why Mumbai is cheap to
build in and expensive to leave, and Zurich is the other way round.

Values are intentionally approximate. The useful comparison is the *ratio*: an
intercontinental gigabyte costs nine times an in-region one in this data, and tuning
does not change which traffic crosses that boundary.

Real prices change over time and by contract. To use them, update these files;
nothing in `losim/` needs to change.

## Egress rates by distance

The rates distinguish same-zone, regional, continental, and intercontinental traffic:

| link | rate | when |
|---|---|---|
| same zone | free | both machines in `eu-central-1a` |
| same region | `egress_per_gb` | `eu-central-1a` -> `eu-central-1b` |
| same continent | `egress_cross_region_per_gb` | `eu-central-1a` -> `eu-west-1a` |
| across an ocean | `egress_intercontinental_per_gb` | `eu-central-1a` -> `ap-northeast-1a` |

A machine's zone determines the rate. losim reads the region from the zone name:
`eu-central-1a` is in `eu-central-1`, and `switzerlandnorth-1` is in
`switzerlandnorth`. No additional declaration is needed.

The trace records the split (`egressMb`, per machine, by destination region) as
the calls happen because only the caller knows both ends. The bill prints separate
egress lines by destination region, so intercontinental traffic remains visible.

A zone that losim does not recognise, such as `rack-3` or `left`, becomes its own
region on an unknown continent. Traffic to it uses the **cross-region** rate;
losim does not infer intercontinental distance from an unknown name.

## Field reference

See [the manual](../docs/ref/prices.mdx).
