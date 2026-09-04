package losim.res;

/** A machine you could actually rent. Resource knobs are derived from this. */
public record InstanceSpec(
        String name,
        String family,
        double vcpu,
        long memoryMb,
        double netGbps,
        long storageGb,
        double onDemandPerHour) {

    /** Reference machine: cost declarations are calibrated against 2 vCPU. */
    public static final double REFERENCE_VCPU = 2.0;

    /** How much slower than the reference this machine is. */
    public double cpuFactor() { return REFERENCE_VCPU / vcpu; }
}
