package com.phzt.railplanner.client.terrain;

import xaero.map.MapProcessor;
import xaero.map.region.MapBlock;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;

/** 从 Xaero 世界地图已探索的数据里读地面高度，不加载区块。 */
public final class XaeroTerrain {

    public static final int UNKNOWN = Integer.MIN_VALUE;

    private XaeroTerrain() {
    }

    public static int height(MapProcessor mp, int x, int z) {
        try {
            int cave = mp.getCurrentCaveLayer();
            MapRegion region = mp.getLeafMapRegion(cave, x >> 9, z >> 9, false);
            if (region == null) {
                return UNKNOWN;
            }
            MapTileChunk chunk = region.getChunk((x >> 6) & 7, (z >> 6) & 7);
            if (chunk == null) {
                return UNKNOWN;
            }
            MapTile tile = chunk.getTile((x >> 4) & 3, (z >> 4) & 3);
            if (tile == null) {
                return UNKNOWN;
            }
            MapBlock block = tile.getBlock(x & 15, z & 15);
            if (block == null) {
                return UNKNOWN;
            }
            int h = block.getEffectiveHeight(false);
            return h == Short.MAX_VALUE ? UNKNOWN : h;
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }
}
