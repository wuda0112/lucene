/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.codecs.simpletext;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.apache.lucene.codecs.CompetitiveImpactAccumulator;
import org.apache.lucene.codecs.MultiLevelSkipListWriter;
import org.apache.lucene.index.Impact;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;

/**
 * plain text skip data.
 *
 * @lucene.experimental
 */
class SimpleTextSkipWriter extends MultiLevelSkipListWriter {

  static final int skipMultiplier = 3;
  static final int maxSkipLevels = 4;

  static final int BLOCK_SIZE = 8;
  private Map<Integer, Boolean> wroteHeaderPerLevelMap = new HashMap<>();
  private int curDoc;
  private long curDocFilePointer;
  private CompetitiveImpactAccumulator[] curCompetitiveFreqNorms;
  private final BytesRefBuilder scratch = new BytesRefBuilder();

  static final BytesRef SKIP_LIST = new BytesRef("    skipList ");
  static final BytesRef LEVEL = new BytesRef("      level ");
  static final BytesRef SKIP_DOC = new BytesRef("        skipDoc ");
  static final BytesRef SKIP_DOC_FP = new BytesRef("        skipDocFP ");
  static final BytesRef IMPACTS = new BytesRef("        impacts ");
  static final BytesRef IMPACT = new BytesRef("          impact ");
  static final BytesRef FREQ = new BytesRef("            freq ");
  static final BytesRef NORM = new BytesRef("            norm ");
  static final BytesRef IMPACTS_END = new BytesRef("        impacts_end ");

  protected SimpleTextSkipWriter(SegmentWriteState writeState) throws IOException {
    super(BLOCK_SIZE, skipMultiplier, maxSkipLevels, writeState.segmentInfo.maxDoc());
    curCompetitiveFreqNorms = new CompetitiveImpactAccumulator[maxSkipLevels];
    for (int i = 0; i < maxSkipLevels; ++i) {
      curCompetitiveFreqNorms[i] = new CompetitiveImpactAccumulator();
    }
    resetSkip();
  }

  @Override
  protected void writeSkipData(int level, DataOutput skipBuffer) throws IOException {
    Boolean wroteHeader = wroteHeaderPerLevelMap.get(level);
    if (wroteHeader == null || !wroteHeader) {
      // because the parent MultiLevelSkipListWriter will write "length"(a VLong) before this new
      // line
      // or write "childPointer"(a VLong) before this
      SimpleTextUtil.writeNewline(skipBuffer);
      SimpleTextUtil.write(skipBuffer, LEVEL);
      SimpleTextUtil.write(skipBuffer, level + "", scratch);

      wroteHeaderPerLevelMap.put(level, true);
    }
    SimpleTextUtil.writeNewline(skipBuffer);
    SimpleTextUtil.write(skipBuffer, SKIP_DOC);
    SimpleTextUtil.write(skipBuffer, curDoc + "", scratch);

    SimpleTextUtil.writeNewline(skipBuffer);
    SimpleTextUtil.write(skipBuffer, SKIP_DOC_FP);
    SimpleTextUtil.write(skipBuffer, curDocFilePointer + "", scratch);

    CompetitiveImpactAccumulator competitiveImpactAccumulator = curCompetitiveFreqNorms[level];
    Collection<Impact> impacts = competitiveImpactAccumulator.getCompetitiveFreqNormPairs();
    assert impacts.size() > 0;
    if (level + 1 < numberOfSkipLevels) {
      curCompetitiveFreqNorms[level + 1].addAll(competitiveImpactAccumulator);
    }
    SimpleTextUtil.writeNewline(skipBuffer);
    SimpleTextUtil.write(skipBuffer, IMPACTS);
    SimpleTextUtil.writeNewline(skipBuffer);
    for (Impact impact : impacts) {
      SimpleTextUtil.write(skipBuffer, IMPACT);
      SimpleTextUtil.writeNewline(skipBuffer);
      SimpleTextUtil.write(skipBuffer, FREQ);
      SimpleTextUtil.write(skipBuffer, impact.freq + "", scratch);
      SimpleTextUtil.writeNewline(skipBuffer);
      SimpleTextUtil.write(skipBuffer, NORM);
      SimpleTextUtil.write(skipBuffer, impact.norm + "", scratch);
      SimpleTextUtil.writeNewline(skipBuffer);
    }
    SimpleTextUtil.write(skipBuffer, IMPACTS_END);
    SimpleTextUtil.writeNewline(skipBuffer);
  }

  @Override
  protected void resetSkip() {
    super.resetSkip();
    wroteHeaderPerLevelMap.clear();
    this.curDoc = -1;
    this.curDocFilePointer = -1;
    for (CompetitiveImpactAccumulator acc : curCompetitiveFreqNorms) {
      acc.clear();
    }
  }

  @Override
  public long writeSkip(IndexOutput output) throws IOException {
    long skipOffset = output.getFilePointer();
    SimpleTextUtil.write(output, SKIP_LIST);
    SimpleTextUtil.writeNewline(output);
    super.writeSkip(output);
    return skipOffset;
  }

  void bufferSkip(
      int doc,
      long docFilePointer,
      int numDocs,
      final CompetitiveImpactAccumulator competitiveImpactAccumulator)
      throws IOException {
    assert doc > curDoc;
    this.curDoc = doc;
    this.curDocFilePointer = docFilePointer;
    this.curCompetitiveFreqNorms[0].addAll(competitiveImpactAccumulator);
    bufferSkip(numDocs);
  }
}
