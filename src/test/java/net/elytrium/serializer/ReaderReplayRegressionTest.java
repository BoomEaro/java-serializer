package net.elytrium.serializer;

import java.io.BufferedReader;
import java.io.StringReader;
import net.elytrium.serializer.language.reader.YamlReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReaderReplayRegressionTest {

  @Test
  void newLookaheadReadsAndPreservesPendingReplay() {
    YamlReader reader = new YamlReader(new BufferedReader(new StringReader("abcd")));
    reader.setSeek();
    Assertions.assertEquals('a', reader.readRaw());
    Assertions.assertEquals('b', reader.readRaw());
    Assertions.assertEquals('c', reader.readRaw());
    reader.unsetSeek();

    Assertions.assertEquals('a', reader.readRaw());
    reader.setSeekFromMarker('a');
    Assertions.assertEquals('b', reader.readRaw());
    reader.clearSeek();
    Assertions.assertEquals('c', reader.readRaw());
    Assertions.assertEquals('d', reader.readRaw());
    Assertions.assertEquals('\0', reader.readRaw());
  }

  @Test
  void repeatedLookaheadRestoresRecordedCharactersBeforeRemainingReplay() {
    YamlReader reader = new YamlReader(new BufferedReader(new StringReader("abcd")));
    reader.setSeek();
    Assertions.assertEquals('a', reader.readRaw());
    Assertions.assertEquals('b', reader.readRaw());
    Assertions.assertEquals('c', reader.readRaw());
    reader.unsetSeek();

    Assertions.assertEquals('a', reader.readRaw());
    reader.setSeekFromMarker('a');
    Assertions.assertEquals('b', reader.readRaw());
    reader.unsetSeek();
    Assertions.assertEquals('a', reader.readRaw());
    Assertions.assertEquals('b', reader.readRaw());
    Assertions.assertEquals('c', reader.readRaw());
    Assertions.assertEquals('d', reader.readRaw());
  }

  @Test
  void restoringLookaheadDoesNotDuplicateReusedLastCharacter() {
    YamlReader reader = new YamlReader(new BufferedReader(new StringReader("abc")));
    reader.setSeek();
    Assertions.assertEquals('a', reader.readRaw());
    Assertions.assertEquals('b', reader.readRaw());
    reader.setReuseBuffer();
    reader.unsetSeek();
    Assertions.assertEquals('a', reader.readRaw());
    Assertions.assertEquals('b', reader.readRaw());
    Assertions.assertEquals('c', reader.readRaw());
  }
}
