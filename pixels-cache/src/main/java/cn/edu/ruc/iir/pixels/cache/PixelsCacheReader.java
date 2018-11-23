package cn.edu.ruc.iir.pixels.cache;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * pixels cache reader.
 *
 * @author guodong
 */
public class PixelsCacheReader
        implements AutoCloseable
{
    private final static int KEY_HEADER_SIZE = 2;
    private final static long CHILDREN_OFFSET_MASK = 0x00FFFFFFFFFFFFFFL;
    private final MemoryMappedFile cacheFile;
    private final MemoryMappedFile indexFile;

    private PixelsCacheReader(MemoryMappedFile cacheFile, MemoryMappedFile indexFile)
    {
        this.cacheFile = cacheFile;
        this.indexFile = indexFile;
    }

    public static class Builder
    {
        private String builderCacheLocation = "";
        private long builderCacheSize;
        private String builderIndexLocation = "";
        private long builderIndexSize;

        private Builder()
        {}

        public PixelsCacheReader.Builder setCacheLocation(String cacheLocation)
        {
            checkArgument(!cacheLocation.isEmpty(), "location should not be empty");
            this.builderCacheLocation = cacheLocation;

            return this;
        }

        public PixelsCacheReader.Builder setCacheSize(long cacheSize)
        {
            checkArgument(cacheSize > 0, "size should be positive");
            this.builderCacheSize = cacheSize;

            return this;
        }

        public PixelsCacheReader.Builder setIndexLocation(String location)
        {
            checkArgument(!location.isEmpty(), "index location should not be empty");
            this.builderIndexLocation = location;

            return this;
        }

        public PixelsCacheReader.Builder setIndexSize(long size)
        {
            checkArgument(size > 0, "index size should be positive");
            this.builderIndexSize = size;

            return this;
        }

        public PixelsCacheReader build() throws Exception
        {
            MemoryMappedFile cacheFile = new MemoryMappedFile(builderCacheLocation, builderCacheSize);
            MemoryMappedFile indexFile = new MemoryMappedFile(builderIndexLocation, builderIndexSize);

            return new PixelsCacheReader(cacheFile, indexFile);
        }
    }

    public static PixelsCacheReader.Builder newBuilder()
    {
        return new PixelsCacheReader.Builder();
    }

    /**
     * Read specified columnlet from cache.
     * If cache is not hit, empty byte array is returned, and an access message is sent to the mq.
     * If cache is hit, columnlet content is returned as byte array.
     * @param blockId block id
     * @param rowGroupId row group id
     * @param columnId column id
     * @return columnlet content
     * */
    public byte[] get(String blockId, short rowGroupId, short columnId)
    {
        byte[] content = new byte[0];
        // check rw flag, if not readable, return empty bytes
        short rwFlag = PixelsCacheUtil.getIndexRW(indexFile);
        if (rwFlag != PixelsCacheUtil.RWFlag.READ.getId()) {
            return content;
        }

        // check if reader count reaches its max value, if so no more reads are allowed
        int readerCount = PixelsCacheUtil.getIndexReaderCount(indexFile);
        if (readerCount >= PixelsCacheUtil.MAX_READER_COUNT) {
            return content;
        }
        // update reader count
        PixelsCacheUtil.indexReaderCountIncrement(indexFile);

        // search index file for columnlet id
        ColumnletId columnletId = new ColumnletId(blockId, rowGroupId, columnId);
        byte[] cacheKeyBytes = columnletId.getBytes();

        // search cache key
        PixelsCacheIdx cacheIdx = search(cacheKeyBytes);
        // if found, read content from cache
        if (cacheIdx != null) {
            long offset = cacheIdx.getOffset();
            int length = cacheIdx.getLength();
            content = new byte[length];
            // read content
            cacheFile.getBytes(offset, content, 0, length);
        }

        // decrease reader count
        PixelsCacheUtil.indexReaderCountDecrement(indexFile);

        return content;
    }

    /**
     * Search key from radix tree.
     * If found, update counter in cache idx.
     * Else, return null
     * */
    private PixelsCacheIdx search(byte[] key)
    {
        final int keyLen = key.length;
        long currentNodeOffset = PixelsCacheUtil.INDEX_RADIX_OFFSET;
        int bytesMatched = 0;
        int bytesMatchedInNodeFound = 0;

        // get root
        int currentNodeHeader = indexFile.getInt(currentNodeOffset);
        int currentNodeChildrenNum = currentNodeHeader & 0x000000FF;
        int currentNodeEdgeSize = currentNodeHeader & 0x7FFFFF00;
        if (currentNodeChildrenNum == 0 && currentNodeEdgeSize == 0) {
            return null;
        }

        // search
        outer_loop: while (bytesMatched < keyLen) {
            // search each child for the matching node
            long matchingChildOffset = 0L;
            for (int i = 0; i < currentNodeChildrenNum; i++) {
                long child = indexFile.getLong(currentNodeOffset + 4 + (4 * i));
                byte leader = (byte) ((child & 0xFF00000000000000L) >>> 24);
                if (leader == key[bytesMatched]) {
                    matchingChildOffset = child & 0x00FFFFFFFFFFFFFFL;
                }
            }
            if (matchingChildOffset == 0) {
                break;
            }

            currentNodeOffset = matchingChildOffset;
            bytesMatchedInNodeFound = 0;
            currentNodeHeader = indexFile.getInt(currentNodeOffset);
            currentNodeChildrenNum = currentNodeHeader & 0x000000FF;
            currentNodeEdgeSize = currentNodeHeader & 0x7FFFFF00;
            byte[] currentNodeEdge = new byte[currentNodeEdgeSize];
            indexFile.getBytes(currentNodeOffset + 4 + currentNodeChildrenNum * 8,
                               currentNodeEdge, 0, currentNodeEdgeSize);
            for (int i = 0, numEdgeBytes = currentNodeEdgeSize; i < numEdgeBytes && bytesMatched < keyLen; i++)
            {
                if (currentNodeEdge[i] != key[bytesMatched]) {
                    break outer_loop;
                }
                bytesMatched++;
                bytesMatchedInNodeFound++;
            }
        }

        // if matches, node found
        if (bytesMatched == keyLen && bytesMatchedInNodeFound == currentNodeEdgeSize) {
            if (((currentNodeHeader >>> 31) & 1) > 0) {
                byte[] idx = new byte[12];
                indexFile.getBytes(currentNodeOffset + 4 + (currentNodeChildrenNum * 8) + currentNodeEdgeSize,
                                         idx, 0, 12);
                return new PixelsCacheIdx(idx);
            }
        }
        return null;
    }

    public void close()
    {
    }
}
