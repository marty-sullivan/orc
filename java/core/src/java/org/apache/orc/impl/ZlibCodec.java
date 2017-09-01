/**
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.orc.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.apache.orc.CompressionCodec;

public class ZlibCodec implements CompressionCodec, DirectDecompressionCodec {
  private static final HadoopShims SHIMS = HadoopShimsFactory.get();
  // Note: shim path does not care about levels and strategies (only used for decompression).
  private HadoopShims.DirectDecompressor decompressShim = null;
  private Boolean direct = null;

  private int level;
  private int strategy;

  public ZlibCodec() {
    level = Deflater.DEFAULT_COMPRESSION;
    strategy = Deflater.DEFAULT_STRATEGY;
  }

  private ZlibCodec(int level, int strategy) {
    this.level = level;
    this.strategy = strategy;
  }

  @Override
  public boolean compress(ByteBuffer in, ByteBuffer out,
                          ByteBuffer overflow) throws IOException {
    int length = in.remaining();
    int outSize = 0;
    Deflater deflater = new Deflater(level, true);
    try {
      deflater.setStrategy(strategy);
      deflater.setInput(in.array(), in.arrayOffset() + in.position(), length);
      deflater.finish();
      int offset = out.arrayOffset() + out.position();
      while (!deflater.finished() && (length > outSize)) {
        int size = deflater.deflate(out.array(), offset, out.remaining());
        out.position(size + out.position());
        outSize += size;
        offset += size;
        // if we run out of space in the out buffer, use the overflow
        if (out.remaining() == 0) {
          if (overflow == null) {
            return false;
          }
          out = overflow;
          offset = out.arrayOffset() + out.position();
        }
      }
    } finally {
      deflater.end();
    }
    return length > outSize;
  }

  @Override
  public void decompress(ByteBuffer in, ByteBuffer out) throws IOException {

    if(in.isDirect() && out.isDirect()) {
      directDecompress(in, out);
      return;
    }

    Inflater inflater = new Inflater(true);
    try {
      inflater.setInput(in.array(), in.arrayOffset() + in.position(),
                        in.remaining());
      while (!(inflater.finished() || inflater.needsDictionary() ||
               inflater.needsInput())) {
        try {
          int count = inflater.inflate(out.array(),
                                       out.arrayOffset() + out.position(),
                                       out.remaining());
          out.position(count + out.position());
        } catch (DataFormatException dfe) {
          throw new IOException("Bad compression data", dfe);
        }
      }
      out.flip();
    } finally {
      inflater.end();
    }
    in.position(in.limit());
  }

  @Override
  public boolean isAvailable() {
    if (direct == null) {
      // see nowrap option in new Inflater(boolean) which disables zlib headers
      try {
        ensureShim();
        direct = (decompressShim != null);
      } catch (UnsatisfiedLinkError ule) {
        direct = Boolean.valueOf(false);
      }
    }
    return direct.booleanValue();
  }

  private void ensureShim() {
    if (decompressShim == null) {
      decompressShim = SHIMS.getDirectDecompressor(
          HadoopShims.DirectCompressionType.ZLIB_NOHEADER);
    }
  }

  @Override
  public void directDecompress(ByteBuffer in, ByteBuffer out) throws IOException {
    ensureShim();
    decompressShim.decompress(in, out);
    out.flip(); // flip for read
  }

  @Override
  public CompressionCodec modify(/* @Nullable */ EnumSet<Modifier> modifiers) {

    if (modifiers == null) {
      return this;
    }

    int l = this.level;
    int s = this.strategy;

    for (Modifier m : modifiers) {
      switch (m) {
      case BINARY:
        /* filtered == less LZ77, more huffman */
        s = Deflater.FILTERED;
        break;
      case TEXT:
        s = Deflater.DEFAULT_STRATEGY;
        break;
      case FASTEST:
        // deflate_fast looking for 8 byte patterns
        l = Deflater.BEST_SPEED;
        break;
      case FAST:
        // deflate_fast looking for 16 byte patterns
        l = Deflater.BEST_SPEED + 1;
        break;
      case DEFAULT:
        // deflate_slow looking for 128 byte patterns
        l = Deflater.DEFAULT_COMPRESSION;
        break;
      default:
        break;
      }
    }
    return new ZlibCodec(l, s);
  }

  @Override
  public void reset() {
    level = Deflater.DEFAULT_COMPRESSION;
    strategy = Deflater.DEFAULT_STRATEGY;
    if (decompressShim != null) {
      decompressShim.reset();
    }
  }

  @Override
  public void close() {
    if (decompressShim != null) {
      decompressShim.end();
    }
  }
}
