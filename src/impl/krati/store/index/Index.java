package krati.store.index;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map.Entry;

/**
 * Index.
 * 
 * @author jwu
 */
public interface Index extends Iterable<Entry<byte[], byte[]>> {
    
    public byte[] lookup(byte[] keyBytes);
    
    public void update(byte[] keyBytes, byte[] metaBytes) throws Exception;
    
    public Iterator<byte[]> keyIterator();
    
    public Iterator<Entry<byte[], byte[]>> iterator();
    
    public void persist() throws IOException;
    
    public void sync() throws IOException;
    
    public void clear() throws IOException;
}
