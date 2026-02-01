package org.ratmuds.minenav.client;

public class CubeData {
    public final float minX, minY, minZ;
    public final float maxX, maxY, maxZ;
    public final float red, green, blue, alpha;

    public CubeData(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                    float red, float green, float blue, float alpha) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
    }

    public static CubeData create(float x, float y, float z, float size, float red, float green, float blue, float alpha) {
        return new CubeData(x, y, z, x + size, y + size, z + size, red, green, blue, alpha);
    }
}
