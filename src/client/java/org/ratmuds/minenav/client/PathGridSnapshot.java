package org.ratmuds.minenav.client;

public final class PathGridSnapshot {
    public final int minX, minY, minZ;
    public final int sizeX, sizeY, sizeZ;
    public final long builtGameTime;
    public final byte[] isAir;
    public volatile double[] costs;

    public PathGridSnapshot(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, long builtGameTime, byte[] isAir) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.builtGameTime = builtGameTime;
        this.isAir = isAir;
    }

    public boolean matches(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
        return this.minX == minX
                && this.minY == minY
                && this.minZ == minZ
                && this.sizeX == sizeX
                && this.sizeY == sizeY
                && this.sizeZ == sizeZ;
    }
}
