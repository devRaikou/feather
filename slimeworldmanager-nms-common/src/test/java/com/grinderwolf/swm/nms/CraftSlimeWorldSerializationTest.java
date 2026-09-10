package com.grinderwolf.swm.nms;

import com.flowpowered.nbt.CompoundMap;
import com.flowpowered.nbt.CompoundTag;
import com.flowpowered.nbt.IntArrayTag;
import com.github.luben.zstd.Zstd;
import com.grinderwolf.swm.api.utils.NibbleArray;
import com.grinderwolf.swm.api.utils.SlimeFormat;
import com.grinderwolf.swm.api.world.SlimeChunk;
import com.grinderwolf.swm.api.world.SlimeChunkSection;
import com.grinderwolf.swm.api.world.properties.SlimePropertyMap;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class CraftSlimeWorldSerializationTest {

    @Test
    public void serializesWorldLargerThanHeapAndPreservesEveryChunk() throws IOException {
        // Share fixture arrays so this tests serialization overhead, not world residency.
        // The encoded raw chunks exceed the 128 MiB heap configured for this module.
        CraftSlimeWorld world = createWorld(1024);
        SlimeChunkSection expected = world.getChunk(0, 0).getSections()[0];
        DataInputStream input = chunkData(world.serialize());
        assertEquals(-1, input.readInt());
        int segments = input.readInt();
        long declaredRawBytes = input.readLong();
        assertTrue(declaredRawBytes > 128L * 1024 * 1024);
        assertTrue(segments > 1);

        long actualRawBytes = 0;
        int chunkCount = 0;
        for (int segment = 0; segment < segments; segment++) {
            int compressedLength = input.readInt();
            int rawLength = input.readInt();
            byte[] compressed = readBytes(input, compressedLength);
            byte[] raw = Zstd.decompress(compressed, rawLength);
            assertEquals(rawLength, raw.length);
            actualRawBytes += raw.length;
            DataInputStream chunks = new DataInputStream(new ByteArrayInputStream(raw));
            while (chunks.available() > 0) {
                for (int i = 0; i < 256; i++) assertEquals(64, chunks.readInt());
                for (int i = 0; i < 256; i++) assertEquals(1, chunks.readInt());
                assertEquals(0xffff, chunks.readUnsignedShort());
                for (int section = 0; section < 16; section++) {
                    assertTrue(chunks.readBoolean());
                    assertArrayEquals(expected.getBlockLight().getBacking(), readBytes(chunks, 2048));
                    assertArrayEquals(expected.getBlocks(), readBytes(chunks, 4096));
                    assertArrayEquals(expected.getData().getBacking(), readBytes(chunks, 2048));
                    assertTrue(chunks.readBoolean());
                    assertArrayEquals(expected.getSkyLight().getBacking(), readBytes(chunks, 2048));
                }
                chunkCount++;
            }
        }

        assertEquals(1024, chunkCount);
        assertEquals(declaredRawBytes, actualRawBytes);
        assertSecondaryBlobs(input);
    }

    @Test
    public void emptyWorldHasNoSegmentsAndKeepsFollowingBlobsAligned() throws IOException {
        DataInputStream input = chunkData(createWorld(0).serialize());
        assertEquals(-1, input.readInt());
        assertEquals(0, input.readInt());
        assertEquals(0, input.readLong());
        assertSecondaryBlobs(input);
    }

    private static CraftSlimeWorld createWorld(int count) {
        byte[] blocks = new byte[4096];
        Arrays.fill(blocks, (byte) 1);
        blocks[0] = (byte) 130;
        NibbleArray data = new NibbleArray(4096);
        data.set(0, 5);
        byte[] skyLight = new byte[2048];
        Arrays.fill(skyLight, (byte) 0xff);
        SlimeChunkSection section = new CraftSlimeChunkSection(blocks, data, null, null,
                new NibbleArray(4096), new NibbleArray(skyLight));
        SlimeChunkSection[] sections = new SlimeChunkSection[16];
        Arrays.fill(sections, section);
        int[] heights = new int[256];
        Arrays.fill(heights, 64);
        CompoundTag heightMaps = new CompoundTag("", new CompoundMap(
                Collections.singletonList(new IntArrayTag("heightMap", heights))));
        int[] biomes = new int[256];
        Arrays.fill(biomes, 1);
        Map<Long, SlimeChunk> chunks = new HashMap<>();
        for (int x = 0; x < count; x++) {
            chunks.put((long) x, new CraftSlimeChunk("memory-test", x, 0, sections, heightMaps, biomes,
                    Collections.emptyList(), Collections.emptyList()));
        }
        return new CraftSlimeWorld(null, "memory-test", chunks, new CompoundTag("", new CompoundMap()),
                Collections.emptyList(), (byte) 1, new SlimePropertyMap(), true, false);
    }

    private static DataInputStream chunkData(byte[] serialized) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(serialized));
        assertArrayEquals(SlimeFormat.SLIME_HEADER, readBytes(input, SlimeFormat.SLIME_HEADER.length));
        assertEquals(SlimeFormat.SLIME_VERSION, input.readByte());
        assertEquals(1, input.readByte());
        input.readShort();
        input.readShort();
        int width = input.readUnsignedShort();
        int depth = input.readUnsignedShort();
        readBytes(input, (width * depth + 7) / 8);
        return input;
    }

    private static void assertSecondaryBlobs(DataInputStream input) throws IOException {
        readBlob(input); // Tile entities
        assertFalse(input.readBoolean()); // No entities in this fixture
        readBlob(input); // Extra data
        readBlob(input); // Maps
        assertEquals(-1, input.read());
    }

    private static void readBlob(DataInputStream input) throws IOException {
        int compressedLength = input.readInt();
        int rawLength = input.readInt();
        byte[] raw = Zstd.decompress(readBytes(input, compressedLength), rawLength);
        assertEquals(rawLength, raw.length);
    }

    private static byte[] readBytes(DataInputStream input, int size) throws IOException {
        byte[] data = new byte[size];
        input.readFully(data);
        return data;
    }
}
