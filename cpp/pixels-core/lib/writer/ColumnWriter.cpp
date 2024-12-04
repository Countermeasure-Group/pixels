//
// Created by gengdy on 24-11-9.
//
#include <utils/ConfigFactory.h>
#include "utils/BitUtils.h"
#include "writer/ColumnWriter.h"

const int ColumnWriter::ISNULL_ALIGNMENT = std::stoi(ConfigFactory::Instance().getProperty("isnull.bitmap.alignment"));
const std::vector<uint8_t> ColumnWriter::ISNULL_PADDING_BUFFER(ColumnWriter::ISNULL_ALIGNMENT, 0);



std::vector<uint8_t> ColumnWriter::getColumnChunkContent() const {
    auto begin = outputStream->getPointer() + outputStream->getReadPos();
    auto end = outputStream->getPointer() + outputStream->getWritePos();
    return std::vector<uint8_t>(begin, end);
}

int ColumnWriter::getColumnChunkSize() const {
    return static_cast<int>(outputStream->getWritePos() - outputStream->getReadPos());
}
pixels::proto::ColumnChunkIndex ColumnWriter::getNewColumnChunkIndex() {
    return pixels::proto::ColumnChunkIndex();
}

std::shared_ptr<pixels::proto::ColumnChunkIndex> ColumnWriter::getColumnChunkIndex() {
    return columnChunkIndex;
}

pixels::proto::ColumnEncoding ColumnWriter::getColumnChunkEncoding() {
    pixels::proto::ColumnEncoding encoding;
    encoding.set_kind(pixels::proto::ColumnEncoding::Kind::ColumnEncoding_Kind_NONE);
    return encoding;
}


void ColumnWriter::flush() {
    if (curPixelEleIndex > 0) {
        newPixel();
    }
    int isNullOffset = static_cast<int>(outputStream->getWritePos());
    if (ISNULL_ALIGNMENT != 0 && isNullOffset % ISNULL_ALIGNMENT != 0) {
        int alignBytes = ISNULL_ALIGNMENT - (isNullOffset % ISNULL_ALIGNMENT);
        outputStream->putBytes(const_cast<uint8_t*>(ISNULL_PADDING_BUFFER.data()), alignBytes);
        isNullOffset += alignBytes;
    }
    columnChunkIndex->set_isnulloffset(isNullOffset);
    outputStream->putBytes(isNullStream->getPointer() + isNullStream->getReadPos(), isNullStream->getWritePos() - isNullStream->getReadPos());
}

void ColumnWriter::newPixel() {
    if (hasNull) {
        auto compacted = BitUtils::bitWiseCompact(isNull, curPixelIsNullIndex, byteOrder);
        isNullStream->putBytes(const_cast<uint8_t*>(compacted.data()), compacted.size());
        pixelStatRecorder.setHasNull();
    }
    curPixelPosition = static_cast<int>(outputStream->getWritePos());
    curPixelEleIndex = 0;
    curPixelVectorIndex = 0;
    curPixelIsNullIndex = 0;

    columnChunkStatRecorder.merge(pixelStatRecorder);

    pixels::proto::PixelStatistic pixelStat;
    *pixelStat.mutable_statistic() = pixelStatRecorder.serialize();
    columnChunkIndex->add_pixelpositions(lastPixelPosition);
    auto new_pixelstatistic = columnChunkIndex->add_pixelstatistics();
    *new_pixelstatistic = pixelStat;

    lastPixelPosition = curPixelPosition;
    pixelStatRecorder.reset();
    hasNull = false;
}

void ColumnWriter::reset() {
    lastPixelPosition = 0;
    curPixelPosition = 0;
    columnChunkIndex->Clear();
    columnChunkStat->Clear();
    pixelStatRecorder.reset();
    columnChunkStatRecorder.reset();
    outputStream->resetPosition();
    isNullStream->resetPosition();
}

void ColumnWriter::close() {
    outputStream->clear();
    isNullStream->clear();
}



ColumnWriter::ColumnWriter(std::shared_ptr<TypeDescription> type,
                                   std::shared_ptr<PixelsWriterOption> writerOption)
        : pixelStride(writerOption->getPixelsStride()),
          encodingLevel(writerOption->getEncodingLevel()),
          byteOrder(writerOption->getByteOrder()),
          nullsPadding(false),// default is false
          isNull(pixelStride, false)

{
    outputStream=std::make_shared<ByteBuffer>();
    isNullStream=std::make_shared<ByteBuffer>();
    columnChunkIndex=std::make_shared<pixels::proto::ColumnChunkIndex>();
    columnChunkIndex->set_littleendian(byteOrder == ByteOrder::PIXELS_LITTLE_ENDIAN);
    columnChunkIndex->set_nullspadding(nullsPadding);
    columnChunkIndex->set_isnullalignment(ISNULL_ALIGNMENT);
}


