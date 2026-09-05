import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.CRC32C;

/** Isolated read-only diagnostic. Mappings live only until this short-lived process exits. */
public class PackReadBenchmark {
  record Entry(String path,long offset,int length,int crc) {}
  static String string(DataInputStream in) throws IOException {
    int n=in.readInt(); if(n<0||n>16*1024*1024)throw new IOException("string length");
    byte[] b=in.readNBytes(n);if(b.length!=n)throw new EOFException();return new String(b,java.nio.charset.StandardCharsets.UTF_8);
  }
  static void full(FileChannel c,ByteBuffer b,long p)throws IOException {
    while(b.hasRemaining()){int n=c.read(b,p+b.position());if(n<=0)throw new EOFException();}
  }
  public static void main(String[] args)throws Exception {
    if(args.length!=3)throw new IllegalArgumentException("pack.spfp accepted-order.spfo comma-separated-modes");
    for(String mode:args[2].split(","))if(!Set.of("heap","direct","mapped","parse","parse-ahead").contains(mode))throw new IllegalArgumentException("unknown mode: "+mode);
    try(FileChannel c=FileChannel.open(Path.of(args[0]),StandardOpenOption.READ)) {
      ByteBuffer h=ByteBuffer.allocate(52);full(c,h,0);h.flip();
      if(h.getInt()!=0x53504650||h.getInt()!=3)throw new IOException("pack format");
      int indexSize=h.getInt();long payload=h.getLong();byte[] digest=new byte[32];h.get(digest);
      if(indexSize<=0||indexSize>256*1024*1024||52L+indexSize+payload!=c.size())throw new IOException("pack length");
      ByteBuffer index=ByteBuffer.allocate(indexSize);full(c,index,52);
      if(!MessageDigest.isEqual(digest,MessageDigest.getInstance("SHA-256").digest(index.array())))throw new IOException("index checksum");
      var in=new DataInputStream(new ByteArrayInputStream(index.array()));String profile=string(in);
      int count=in.readInt();if(count<1||count>100000)throw new IOException("entry count");
      Map<String,Entry> entries=new LinkedHashMap<>();long end=0;
      for(int i=0;i<count;i++) {String p=string(in);long o=in.readLong();int n=in.readInt(),crc=in.readInt();
        if(o!=end||n<=0||o+n>payload||entries.put(p,new Entry(p,52L+indexSize+o,n,crc))!=null)throw new IOException("entry range");end+=n;}
      if(end!=payload||in.available()!=0)throw new IOException("payload coverage");
      byte[] ob=Files.readAllBytes(Path.of(args[1]));var oh=ByteBuffer.wrap(ob);
      if(oh.getInt()!=0x5350464f||oh.getInt()!=2)throw new IOException("order format");int os=oh.getInt();
      if(os<0||os+44!=ob.length)throw new IOException("order length");
      if(!MessageDigest.isEqual(Arrays.copyOfRange(ob,12+os,ob.length),MessageDigest.getInstance("SHA-256").digest(Arrays.copyOfRange(ob,12,12+os))))throw new IOException("order checksum");
      var oi=new DataInputStream(new ByteArrayInputStream(ob,12,os));if(!profile.equals(string(oi)))throw new IOException("profile");
      int oc=oi.readInt();if(oc<=0||oc>count)throw new IOException("accepted order count");var ordered=new ArrayList<Entry>();
      for(int i=0;i<oc;i++){Entry e=entries.get(string(oi));if(e==null)throw new IOException("missing entry");ordered.add(e);}
      System.out.printf("{\"profile\":\"%s\",\"entries\":%d,\"selected\":%d,\"packBytes\":%d,\"java\":\"%s\"}%n",profile,count,oc,c.size(),System.getProperty("java.version"));
      final int window=256*1024*1024;var maps=new ArrayList<MappedByteBuffer>();
      byte[] bytes=new byte[65536];ByteBuffer heap=ByteBuffer.wrap(bytes),direct=ByteBuffer.allocateDirect(bytes.length);
      for(String mode:args[2].split(",")) {
        long started=System.nanoTime(),ioNanos=0,crcNanos=0,total=0,calls=0;
        if(mode.startsWith("parse")) {
          System.setProperty("preflight.texture.packReadAhead",Boolean.toString(mode.equals("parse-ahead")));
          Class<?> pc=Class.forName("dev.starsector.preflight.core.PreparedTexturePack");
          var open=Class.forName("dev.starsector.preflight.core.PreparedTexturePackIO").getMethod("open",Path.class,String.class,Collection.class);
          var read=pc.getMethod("readTrusted",String.class);
          var size=Class.forName("dev.starsector.preflight.core.PreparedTexture").getMethod("pixelBytes");
          try(AutoCloseable pack=(AutoCloseable)open.invoke(null,Path.of(args[0]),profile,entries.keySet())) {
            for(Entry e:ordered)total+=(int)size.invoke(read.invoke(pack,e.path()));
            System.out.printf(Locale.ROOT,"{\"mode\":\"%s\",\"millis\":%.3f,\"decodedBytes\":%d}%n",mode,(System.nanoTime()-started)/1e6,total);
            System.out.println(pc.getMethod("readAheadTelemetry").invoke(pack));
          }
          continue;
        }
        if(mode.equals("mapped")&&maps.isEmpty())for(long p=0;p<c.size();p+=window)maps.add(c.map(FileChannel.MapMode.READ_ONLY,p,Math.min(window,c.size()-p)));
        for(Entry e:ordered){var crc=new CRC32C();int done=0;
          while(done<e.length()) {int n=Math.min(bytes.length,e.length()-done);long p=e.offset()+done,t=System.nanoTime();
            if(mode.equals("mapped")){int at=(int)(p/window),offset=(int)(p%window);n=Math.min(n,maps.get(at).capacity()-offset);maps.get(at).get(offset,bytes,0,n);}
            else {ByteBuffer dest=mode.equals("direct")?direct:heap;dest.clear().limit(n);full(c,dest,p);if(mode.equals("direct"))direct.get(0,bytes,0,n);}
            ioNanos+=System.nanoTime()-t;t=System.nanoTime();crc.update(bytes,0,n);crcNanos+=System.nanoTime()-t;done+=n;total+=n;calls++;}
          if((int)crc.getValue()!=e.crc())throw new IOException("entry CRC mismatch");}
        System.out.printf(Locale.ROOT,"{\"mode\":\"%s\",\"millis\":%.3f,\"readCopyMillis\":%.3f,\"crcMillis\":%.3f,\"bytes\":%d,\"chunks\":%d}%n",mode,(System.nanoTime()-started)/1e6,ioNanos/1e6,crcNanos/1e6,total,calls);
      }
    }
  }
}
