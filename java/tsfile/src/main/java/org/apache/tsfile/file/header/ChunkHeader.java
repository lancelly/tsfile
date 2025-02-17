/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.tsfile.file.header;

import org.apache.tsfile.common.conf.TSFileConfig;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.file.MetaMarker;
import org.apache.tsfile.file.metadata.TimeseriesMetadata;
import org.apache.tsfile.file.metadata.enums.CompressionType;
import org.apache.tsfile.file.metadata.enums.TSEncoding;
import org.apache.tsfile.read.TsFileSequenceReader;
import org.apache.tsfile.read.reader.TsFileInput;
import org.apache.tsfile.utils.Pair;
import org.apache.tsfile.utils.RamUsageEstimator;
import org.apache.tsfile.utils.ReadWriteForEncodingUtils;
import org.apache.tsfile.utils.ReadWriteIOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.function.LongConsumer;

public class ChunkHeader {

  public static final long INSTANCE_SIZE =
      RamUsageEstimator.shallowSizeOfInstance(ChunkHeader.class)
          + RamUsageEstimator.shallowSizeOfInstance(TSDataType.class)
          + RamUsageEstimator.shallowSizeOfInstance(CompressionType.class)
          + RamUsageEstimator.shallowSizeOfInstance(TSEncoding.class);

  /**
   * 1 means this chunk has more than one page, so each page has its own page statistic. 5 means
   * this chunk has only one page, and this page has no page statistic.
   *
   * <p>if the 8th bit of this byte is 1 means this chunk is a time chunk of one vector if the 7th
   * bit of this byte is 1 means this chunk is a value chunk of one vector
   */
  private byte chunkType;

  private String measurementID;
  private int dataSize;
  private final TSDataType dataType;
  private final CompressionType compressionType;
  private final TSEncoding encodingType;

  // the following fields do not need to be serialized.
  private int numOfPages;
  private final int serializedSize;

  public ChunkHeader(
      String measurementID,
      int dataSize,
      TSDataType dataType,
      CompressionType compressionType,
      TSEncoding encoding,
      int numOfPages) {
    this(measurementID, dataSize, dataType, compressionType, encoding, numOfPages, 0);
  }

  public ChunkHeader(
      String measurementID,
      int dataSize,
      TSDataType dataType,
      CompressionType compressionType,
      TSEncoding encoding,
      int numOfPages,
      int mask) {
    this(
        (byte)
            ((numOfPages <= 1 ? MetaMarker.ONLY_ONE_PAGE_CHUNK_HEADER : MetaMarker.CHUNK_HEADER)
                | (byte) mask),
        measurementID,
        dataSize,
        getSerializedSize(measurementID, dataSize),
        dataType,
        compressionType,
        encoding);
    this.numOfPages = numOfPages;
  }

  public ChunkHeader(
      byte chunkType,
      String measurementID,
      int dataSize,
      TSDataType dataType,
      CompressionType compressionType,
      TSEncoding encoding) {
    this(
        chunkType,
        measurementID,
        dataSize,
        getSerializedSize(measurementID, dataSize),
        dataType,
        compressionType,
        encoding);
  }

  public ChunkHeader(
      byte chunkType,
      String measurementID,
      int dataSize,
      int headerSize,
      TSDataType dataType,
      CompressionType compressionType,
      TSEncoding encoding) {
    this.chunkType = chunkType;
    this.measurementID = measurementID;
    this.dataSize = dataSize;
    this.dataType = dataType;
    this.compressionType = compressionType;
    this.encodingType = encoding;
    this.serializedSize = headerSize;
  }

  /** the exact serialized size of chunk header. */
  public static int getSerializedSize(String measurementID, int dataSize) {
    int measurementIdLength =
        measurementID == null ? 0 : measurementID.getBytes(TSFileConfig.STRING_CHARSET).length;
    return Byte.BYTES // chunkType
        + ReadWriteForEncodingUtils.varIntSize(measurementIdLength) // measurementID length
        + measurementIdLength // measurementID
        + ReadWriteForEncodingUtils.uVarIntSize(dataSize) // dataSize
        + TSDataType.getSerializedSize() // dataTypex
        + CompressionType.getSerializedSize() // compressionType
        + TSEncoding.getSerializedSize(); // encodingType
  }

  /**
   * The estimated serialized size of chunk header. Only used when we don't know the actual dataSize
   * attribute
   */
  public static int getSerializedSize(String measurementID) {

    int measurementIdLength = measurementID.getBytes(TSFileConfig.STRING_CHARSET).length;
    return Byte.BYTES // chunkType
        + ReadWriteForEncodingUtils.varIntSize(measurementIdLength) // measurementID length
        + measurementIdLength // measurementID
        + Integer.BYTES
        + 1 // uVarInt dataSize
        + TSDataType.getSerializedSize() // dataType
        + CompressionType.getSerializedSize() // compressionType
        + TSEncoding.getSerializedSize(); // encodingType
  }

  public int getSerializedSize() {
    return serializedSize;
  }

  /**
   * deserialize from inputStream, the marker has already been read.
   *
   * @return ChunkHeader the ChunkHeader read from inputStream
   * @throws IOException exception when reading stream
   */
  public static ChunkHeader deserializeFrom(InputStream inputStream, byte chunkType)
      throws IOException {
    // read measurementID
    String measurementID = ReadWriteIOUtils.readVarIntString(inputStream);
    int dataSize = ReadWriteForEncodingUtils.readUnsignedVarInt(inputStream);
    TSDataType dataType = ReadWriteIOUtils.readDataType(inputStream);
    CompressionType type = ReadWriteIOUtils.readCompressionType(inputStream);
    TSEncoding encoding = ReadWriteIOUtils.readEncoding(inputStream);
    return new ChunkHeader(chunkType, measurementID, dataSize, dataType, type, encoding);
  }

  /**
   * deserialize from TsFileInput, the marker has not been read.
   *
   * @param input TsFileInput
   * @param offset offset
   * @return CHUNK_HEADER object
   * @throws IOException IOException
   */
  public static ChunkHeader deserializeFrom(TsFileInput input, long offset) throws IOException {
    return deserializeFrom(input, offset, null);
  }

  /**
   * deserialize from TsFileInput, the marker has not been read.
   *
   * @param input TsFileInput
   * @param offset offset
   * @param ioSizeRecorder can be null
   * @return CHUNK_HEADER object
   * @throws IOException IOException
   */
  public static ChunkHeader deserializeFrom(
      TsFileInput input, long offset, LongConsumer ioSizeRecorder) throws IOException {

    // only 6 bytes, no need to call ioSizeRecorder.accept alone, combine into the remaining raed
    // operation
    ByteBuffer buffer = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + 1);
    input.read(buffer, offset);
    buffer.flip();

    // read chunk header from input to buffer
    byte chunkType = buffer.get();
    int strLength = ReadWriteForEncodingUtils.readVarInt(buffer);
    int alreadyReadLength = buffer.position();

    int remainingBytes =
        strLength
            + Integer.BYTES
            + 1 // uVarInt dataSize
            + TSDataType.getSerializedSize() // dataType
            + CompressionType.getSerializedSize() // compressionType
            + TSEncoding.getSerializedSize();
    buffer = ByteBuffer.allocate(remainingBytes);

    if (ioSizeRecorder != null) {
      ioSizeRecorder.accept((long) alreadyReadLength + remainingBytes);
    }

    input.read(buffer, offset + alreadyReadLength);
    buffer.flip();

    // read measurementID
    String measurementID = ReadWriteIOUtils.readStringWithLength(buffer, strLength);
    int dataSize = ReadWriteForEncodingUtils.readUnsignedVarInt(buffer);
    TSDataType dataType = ReadWriteIOUtils.readDataType(buffer);
    CompressionType type = ReadWriteIOUtils.readCompressionType(buffer);
    TSEncoding encoding = ReadWriteIOUtils.readEncoding(buffer);
    int chunkHeaderSize = alreadyReadLength + buffer.position();
    return new ChunkHeader(
        chunkType, measurementID, dataSize, chunkHeaderSize, dataType, type, encoding);
  }

  /**
   * Used by {@link
   * TsFileSequenceReader#readTimeseriesCompressionTypeAndEncoding(TimeseriesMetadata)} to only
   * decode data size, {@link CompressionType} and {@link TSEncoding}.
   *
   * @param inputStream input stream
   * @return - Compression type and encoding.
   * @throws IOException - If an I/O error occurs.
   */
  public static Pair<CompressionType, TSEncoding> deserializeCompressionTypeAndEncoding(
      InputStream inputStream) throws IOException {
    ReadWriteForEncodingUtils.readUnsignedVarInt(inputStream);
    ReadWriteIOUtils.skip(inputStream, Byte.BYTES); // skip Data type
    CompressionType type = ReadWriteIOUtils.readCompressionType(inputStream);
    TSEncoding encoding = ReadWriteIOUtils.readEncoding(inputStream);
    return new Pair<>(type, encoding);
  }

  public String getMeasurementID() {
    return measurementID;
  }

  public void setMeasurementID(String measurementID) {
    this.measurementID = measurementID;
  }

  public int getDataSize() {
    return dataSize;
  }

  public TSDataType getDataType() {
    return dataType;
  }

  /**
   * serialize to outputStream.
   *
   * @param outputStream outputStream
   * @return length
   * @throws IOException IOException
   */
  public int serializeTo(OutputStream outputStream) throws IOException {
    int length = 0;
    length += ReadWriteIOUtils.write(chunkType, outputStream);
    length += ReadWriteIOUtils.writeVar(measurementID, outputStream);
    length += ReadWriteForEncodingUtils.writeUnsignedVarInt(dataSize, outputStream);
    length += ReadWriteIOUtils.write(dataType, outputStream);
    length += ReadWriteIOUtils.write(compressionType, outputStream);
    length += ReadWriteIOUtils.write(encodingType, outputStream);
    return length;
  }

  /**
   * serialize to ByteBuffer.
   *
   * @param buffer ByteBuffer
   * @return length
   */
  public int serializeTo(ByteBuffer buffer) {
    int length = 0;
    length += ReadWriteIOUtils.write(chunkType, buffer);
    length += ReadWriteIOUtils.writeVar(measurementID, buffer);
    length += ReadWriteForEncodingUtils.writeUnsignedVarInt(dataSize, buffer);
    length += ReadWriteIOUtils.write(dataType, buffer);
    length += ReadWriteIOUtils.write(compressionType, buffer);
    length += ReadWriteIOUtils.write(encodingType, buffer);
    return length;
  }

  public int getNumOfPages() {
    return numOfPages;
  }

  public CompressionType getCompressionType() {
    return compressionType;
  }

  public TSEncoding getEncodingType() {
    return encodingType;
  }

  @Override
  public String toString() {
    return "CHUNK_HEADER{"
        + "measurementID='"
        + measurementID
        + '\''
        + ", dataSize="
        + dataSize
        + ", dataType="
        + dataType
        + ", compressionType="
        + compressionType
        + ", encodingType="
        + encodingType
        + ", numOfPages="
        + numOfPages
        + ", serializedSize="
        + serializedSize
        + '}';
  }

  public void mergeChunkHeader(ChunkHeader chunkHeader) {
    this.dataSize += chunkHeader.getDataSize();
    this.numOfPages += chunkHeader.getNumOfPages();
  }

  public void setDataSize(int dataSize) {
    this.dataSize = dataSize;
  }

  public byte getChunkType() {
    return chunkType;
  }

  public void setChunkType(byte chunkType) {
    this.chunkType = chunkType;
  }

  public void increasePageNums(int i) {
    numOfPages += i;
  }
}
