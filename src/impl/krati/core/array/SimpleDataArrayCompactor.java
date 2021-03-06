package krati.core.array;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.log4j.Logger;

import krati.core.segment.AddressFormat;
import krati.core.segment.MemorySegment;
import krati.core.segment.Segment;
import krati.core.segment.SegmentManager;
import krati.util.Chronos;

/**
 * SimpleDataArray Compactor.
 * 
 * The compaction is a two-stage process: inspect and compact.
 * 
 * The "inspect" determines which segments are eligible for compaction according to
 * a predefined load factor. It returns no more than 3 source segments for compaction.
 * 
 * The "compact" transfers bytes from source segments to a target segment via zero-copy.
 * It batches compaction update records and sends them to the writer for post-processing. 
 * 
 * @author jwu
 *
 */
class SimpleDataArrayCompactor implements Runnable
{
    private final static Logger _log = Logger.getLogger(SimpleDataArrayCompactor.class);
    private final ExecutorService _executor = Executors.newFixedThreadPool(1);
    private final SimpleDataArray _dataArray;
    private volatile double _compactLoadFactor;
    private volatile State _state = State.DONE;
    
    /**
     * Reclaim segments in _segSourceList and transfer their content to _segTarget.
     */
    private volatile Segment _segTarget;
    private final ArrayList<Segment> _segSourceList;
    
    /**
     * Lock for synchronizing compactor executions.
     */
    private final ReentrantLock _lock = new ReentrantLock();
    
    /**
     * Manage compaction updates that will be consumed by the writer.
     */
    private final CompactionUpdateManager _updateManager;
    
    /**
     * The writer signals the compactor to start a new compaction cycle. 
     */
    private final AtomicBoolean _newCycle = new AtomicBoolean(false);
    
    /**
     * Blocking queue for the compactor to send the writer the target segment as nextSegment. 
     */
    private final ArrayBlockingQueue<Segment> _targetQueue =
        new ArrayBlockingQueue<Segment>(1);
    
    /**
     * Queue for segments compacted successfully by the compactor. 
     */
    private final ConcurrentLinkedQueue<Segment> _compactedQueue =
        new ConcurrentLinkedQueue<Segment>();
    
    /**
     * Permits for the writer to get next segment without being blocked.
     */
    private final AtomicInteger _segPermits = new AtomicInteger(0);
    
    /**
     * A byte buffer from transferring bytes to a target segment. 
     */
    private ByteBuffer _buffer = null;
    
    /**
     * Constructs a DataArrayCompactor with the setting below:
     * 
     * <pre>
     *   Compact Load Factor : 0.5
     *   Compact batch Size  : 1000
     * </pre>
     * 
     * A segment is eligible for compaction only if its load factor is less than
     * the default compact load factor (0.5).
     * 
     * @param dataArray          the data array to compact
     */
    public SimpleDataArrayCompactor(SimpleDataArray dataArray)
    {
        this(dataArray, 0.5, 1000);
    }
    
    /**
     * Constructs a DataArrayCompactor with a specified compact load factor and a default compact batch size (1000).
     * 
     * A segment is eligible for compaction only if its load factor is less than
     * the user-specified compact load factor.
     * 
     * @param dataArray          the data array to compact
     * @param compactLoadFactor  the load factor below which a segment is eligible for compaction
     */
    public SimpleDataArrayCompactor(SimpleDataArray dataArray, double compactLoadFactor)
    {
        this(dataArray, compactLoadFactor, 1000);
    }
    
    /**
     * Constructs a DataArrayCompactor with a specified compact load factor and compact batch size.
     * 
     * A segment is eligible for compaction only if its load factor is less than
     * the user-specified compact load factor.
     * 
     * @param dataArray          the data array to compact
     * @param compactLoadFactor  the load factor below which a segment is eligible for compaction
     * @param compactBatchSize   the size of compaction updates delivered by the compactor to the writer. 
     */
    public SimpleDataArrayCompactor(SimpleDataArray dataArray, double compactLoadFactor, int compactBatchSize)
    {
        this._dataArray = dataArray;
        this._compactLoadFactor = compactLoadFactor;
        this._segSourceList = new ArrayList<Segment>();
        this._updateManager = new CompactionUpdateManager(_dataArray, compactBatchSize);
    }
    
    public double getCompactLoadFactor()
    {
        return this._compactLoadFactor;
    }
    
    private static Comparator<Segment> _segmentLoadCmp = new Comparator<Segment>()
    {
        @Override
        public int compare(Segment s1, Segment s2)
        {
            double load1 = s1.getLoadSize();
            double load2 = s2.getLoadSize();
            return (load1 < load2) ? -1 : ((load1 == load2) ? 0 : 1);
        }
    };
    
    private boolean inspect() throws IOException
    {
        SegmentManager segManager = _dataArray.getSegmentManager();
        if(segManager == null) return false;
        
        synchronized(segManager)
        {
            Segment segCurrent = _dataArray.getCurrentSegment();
            
            /*
             * Find source segments that are least loaded.
             * The source segments must be in the READ_ONLY mode.
             */
            ArrayList<Segment> recycleList = new ArrayList<Segment>();
            int cnt = segManager.getSegmentCount();
            for(int i = 0; i < cnt; i++)
            {
                Segment seg = segManager.getSegment(i);
                if(seg != null && seg.getMode() == Segment.Mode.READ_ONLY && seg != segCurrent)
                {
                    if (seg.getLoadFactor() < _compactLoadFactor)
                    {
                        recycleList.add(seg);
                    }
                }
            }
            
            // No segment need compaction
            if (recycleList.size() == 0)
            {
                _segPermits.set(0);
                return false;
            }
            
            // Sort recycleList in ascending order of load size
            Collections.sort(recycleList, _segmentLoadCmp);
            
            // Compact no more than 3 segments per compaction cycle.
            // The total of segment load factors need to be less than
            // 0.8 to allow 20% inaccuracy (for safety).
            double totalFactor = 0;
            for(int i = 0, len = Math.min(3, recycleList.size()); i < len; i++)
            {
                Segment seg = recycleList.get(i);
                if(totalFactor < 0.8)
                {
                    totalFactor += seg.getLoadFactor();
                    if(totalFactor < 0.8)
                    {
                        _segSourceList.add(seg);
                    }
                }
                else
                {
                    break;
                }
            }
            
            // Delay compaction if only one segment is eligible for compaction but it is not VERY fragmented.
            if (_segSourceList.size() == 1 && _segSourceList.get(0).getLoadFactor() > (_compactLoadFactor/2)) return false;
            
            for(Segment seg : _segSourceList)
            {
                _log.info("Segment " + seg.getSegmentId() + " load factor=" + ((long)(seg.getLoadFactor() * 10000) / 10000.0));
            }
            
            _segPermits.set(_segSourceList.size() - 1);
            _log.info("inspect done");
            return true;
        }
        
        /*
         * No synchronization on segManager is required after locating segments eligible for compaction.
         */
    }
    
    private boolean compact() throws IOException
    {
        try
        {
            _segTarget = _dataArray.getSegmentManager().nextSegment();
            for(Segment seg : _segSourceList)
            {
                if(compact(seg, _segTarget))
                {
                    _compactedQueue.add(seg);
                }
                else
                {
                    break;
                }
            }
            
            _targetQueue.add(_segTarget);
            _log.info("bytes transferred to   " + _segTarget.getSegmentId() + ": " + (_segTarget.getAppendPosition() - Segment.dataStartPosition));
        }
        catch(Exception e)
        {
            _log.warn(e.getMessage(), e);
            return false;
        }
        
        _log.info("compact done");
        return true;
    }
    
    private boolean compact(Segment segment, Segment segTarget) throws IOException
    {
        Segment segSource = segment; 
        int segSourceId = segSource.getSegmentId();
        int segTargetId = segTarget.getSegmentId();
        
        Chronos c = new Chronos();
        if(!segment.canReadFromBuffer() && segment.getLoadFactor() > 0.1)
        {
            segSource = new BufferedSegment(segment, getByteBuffer((int)segment.getInitialSize()));
            _log.info("buffering time: " + c.tick() + " ms");
        }
        
        long sizeLimit = segTarget.getInitialSize();
        long bytesTransferred = 0;
        boolean succ = true;
        
        try
        {
            AddressFormat addrFormat = _dataArray._addressFormat;
            
            for(int index = 0, cnt = _dataArray.length(); index < cnt; index++)
            {
                long oldAddress = _dataArray.getAddress(index);
                int oldSegPos = addrFormat.getOffset(oldAddress);
                int oldSegInd = addrFormat.getSegment(oldAddress);
                int length = addrFormat.getDataSize(oldAddress);
                
                if (oldSegInd == segSourceId && oldSegPos >= Segment.dataStartPosition)
                {
                    if(length == 0) length = segSource.readInt(oldSegPos);
                    int byteCnt = 4 + length;
                    long newSegPos = segTarget.getAppendPosition();
                    long newAddress = addrFormat.composeAddress((int)newSegPos, segTargetId, length);
                    
                    if(segTarget.getAppendPosition() + byteCnt >= sizeLimit)
                    {
                        succ = false;
                        break;
                    }
                    
                    // Transfer bytes from source to target
                    segSource.transferTo(oldSegPos, byteCnt, segTarget);
                    bytesTransferred += byteCnt;
                    
                    _updateManager.addUpdate(index, byteCnt, newAddress, oldAddress, segTarget);
                }
            }
            
            // Push whatever left into update queue
            _updateManager.endUpdate(segTarget);
            _log.info("bytes transferred from " + segSource.getSegmentId() + ": " + bytesTransferred + " time: " + c.tick() + " ms");
            
            segTarget.force();
            return succ;
        }
        finally
        {
            if(segSource.getClass() == BufferedSegment.class)
            {
                segSource.close(false);
                segSource = null;
            }
        }
    }
    
    @Override
    public void run()
    {
        while(true)
        {
            if(_newCycle.compareAndSet(true, false))
            {
                // One and only one compactor is at work.
                _lock.lock();
                
                try
                {
                    reset();
                    _state = State.INIT;
                    _log.info("cycle started");
                    
                    // Inspect the array
                    if(!inspect()) continue;
                    
                    // Compact the array
                    if(!compact()) continue;
                }
                catch(Exception e)
                {
                    e.printStackTrace(System.err);
                    _log.error("failed to compact: " + e.getMessage());
                }
                finally
                {
                    reset();
                    _state = State.DONE;
                    _log.info("cycle ended");
                    _lock.unlock();
                }
            }
            else
            {
                try
                {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    _log.warn(e.getMessage());
                }
            }
        }
    }
    
    final void start()
    {
        _executor.execute(this);
    }
    
    public boolean isStarted()
    {
        return _state != State.DONE;
    }
    
    protected void reset()
    {
        _segTarget = null;
        _segPermits.set(0);
        _segSourceList.clear();
        _updateManager.clear();
    }
    
    protected Segment peekTargetSegment()
    {
        return _targetQueue.peek();
    }
    
    protected Segment pollTargetSegment()
    {
        return _targetQueue.poll();
    }
    
    protected CompactionUpdateBatch pollCompactionBatch()
    {
        return _updateManager.pollBatch();
    }
    
    protected boolean recycleCompactionBatch(CompactionUpdateBatch batch)
    {
        return _updateManager.recycleBatch(batch);
    }
    
    protected ByteBuffer getByteBuffer(int bufferLength)
    {
        if(_buffer == null)
        {
            _buffer = ByteBuffer.wrap(new byte[bufferLength]);
            _log.info("ByteBuffer allocated for buffering");
        }
        
        return _buffer;
    }
    
    final ConcurrentLinkedQueue<Segment> getCompactedQueue()
    {
        return _compactedQueue;
    }
    
    final boolean getAndDecrementSegmentPermit()
    {
        return _segPermits.getAndDecrement() > 0;
    }
    
    final Segment getTargetSegment()
    {
        return _segTarget;
    }
    
    final void startsCycle()
    {
        _newCycle.set(true);
    }
    
    static enum State
    {
        INIT,
        DONE;
    }
    
    static class CompactionUpdate
    {
        int _index;
        int _dataSize;
        long _dataAddr;
        long _origAddr;
        
        CompactionUpdate(int index, int dataSize, long dataAddr, long origAddr)
        {
            this._index = index;
            this._dataSize = dataSize;
            this._dataAddr = dataAddr;
            this._origAddr = origAddr;
        }
        
        public String toString()
        {
            StringBuffer buf = new StringBuffer();
            
            buf.append(getClass().getSimpleName());
            buf.append("{index=");
            buf.append(_index);
            buf.append(",  dataSize=");
            buf.append(_dataSize);
            buf.append(",  dataAddr=");
            buf.append(_dataAddr);
            buf.append(",  origAddr=");
            buf.append(_origAddr);
            buf.append("}");
            
            return buf.toString();
        }
    }
    
    static class CompactionUpdateBatch
    {
        static int _counter = 0;
        final int _batchId;
        final int _capacity;
        final int _unitSize = 24;
        final ByteBuffer _buffer;
        
        Segment _segTarget = null;
        int _dataSizeTotal = 0;
        int _serviceId = 0;
        long _lwMark = 0;
        
        CompactionUpdateBatch(int capacity)
        {
            this._capacity = capacity;
            this._batchId = _counter++;
            this._buffer = ByteBuffer.allocate(_capacity * _unitSize);
            _log.info("CompactionUpdateBatch " + _batchId);
        }
        
        public void clear()
        {
            _buffer.clear();
            _segTarget = null;
            _dataSizeTotal = 0;
            _serviceId = 0;
            _lwMark = 0;
        }
        
        public int getCapacity()
        {
            return _capacity;
        }
        
        public int getByteCapacity()
        {
            return _buffer.capacity();
        }
        
        public ByteBuffer getInternalBuffer()
        {
            return _buffer;
        }
        
        public int size()
        {
            return _buffer.position()/_unitSize;
        }
        
        public boolean isEmpty()
        {
            return _buffer.position() == 0;
        }
        
        public int getBatchId()
        {
            return _batchId;
        }

        public int getServiceId()
        {
            return _serviceId;
        }
        
        public String getDescriptiveId()
        {
            return ((_segTarget == null) ? "?[" : (_segTarget.getSegmentId() + "[")) + _serviceId + "]";
        }
        
        public long getLWMark()
        {
            return _lwMark;
        }
        
        public Segment getTargetSegment()
        {
            return _segTarget;
        }
        
        public void add(int index, int dataSize, long dataAddr, long origAddr)
        {
            _buffer.putInt(index);
            _buffer.putInt(dataSize);
            _buffer.putLong(dataAddr);
            _buffer.putLong(origAddr);
            _dataSizeTotal += dataSize;
        }
        
        public CompactionUpdate get(int i)
        {
            return new CompactionUpdate(getUpdateIndex(i),
                                        getUpdateDataSize(i),
                                        getUpdateDataAddr(i),
                                        getOriginDataAddr(i));
        }
        
        public int getUpdateIndex(int i)
        {
            return _buffer.getInt(i * _unitSize);
        }
        
        public int getUpdateDataSize(int i)
        {
            return _buffer.getInt((i * _unitSize) + 4);
        }
        
        public long getUpdateDataAddr(int i)
        {
            return _buffer.getLong((i * _unitSize) + 8);
        }
        
        public long getOriginDataAddr(int i)
        {
            return _buffer.getLong((i * _unitSize) + 16);
        }
        
        public int getDataSizeTotal()
        {
            return _dataSizeTotal;
        }
        
        void setLWMark(long waterMark)
        {
            _lwMark = waterMark;
        }
        
        void setTargetSegment(Segment seg)
        {
            _segTarget = seg;
        }
        
        void setServiceId(int serviceId)
        {
            _serviceId = serviceId;
        }
    }
    
    static class CompactionUpdateManager
    {
        private final int _batchSize;
        private final ConcurrentLinkedQueue<CompactionUpdateBatch> _serviceBatchQueue;
        private final ConcurrentLinkedQueue<CompactionUpdateBatch> _recycleBatchQueue;
        private final SimpleDataArray _dataArray;
        private int _batchServiceIdCounter = 0; 
        private CompactionUpdateBatch _batch;
        
        public CompactionUpdateManager(SimpleDataArray dataArray, int batchSize)
        {
            _dataArray = dataArray;
            _batchSize = batchSize;
            _serviceBatchQueue = new ConcurrentLinkedQueue<CompactionUpdateBatch>();
            _recycleBatchQueue = new ConcurrentLinkedQueue<CompactionUpdateBatch>();
            nextBatch();
        }
        
        private void nextBatch()
        {
            _batch = _recycleBatchQueue.poll();
            if(_batch == null)
            {
                _batch = new CompactionUpdateBatch(_batchSize);
            }
            
            _batch.clear();
            _batch.setServiceId(_batchServiceIdCounter++);
        }
        
        public boolean isServiceQueueEmpty()
        {
            return _serviceBatchQueue.isEmpty();
        }

        public boolean isRecycleQueueEmpty()
        {
            return _recycleBatchQueue.isEmpty();
        }
        
        public CompactionUpdateBatch pollBatch()
        {
            return _serviceBatchQueue.poll();
        }

        public boolean recycleBatch(CompactionUpdateBatch batch)
        {
            batch.clear();
            return _recycleBatchQueue.add(batch);
        }
        
        public void addUpdate(int index, int dataSize, long dataAddr, long origAddr, Segment segTarget) throws IOException
        {
            try
            {
                _batch.add(index, dataSize, dataAddr, origAddr);
            }
            catch(BufferOverflowException e)
            {
                segTarget.force();
                _batch.setTargetSegment(segTarget);
                _batch.setLWMark(_dataArray.getLWMark());
                _log.info("compaction batch " + _batch.getDescriptiveId() + " hwMark=" + _batch.getLWMark());
                
                _serviceBatchQueue.add(_batch);
                nextBatch();
                
                // Add compaction update to new batch
                _batch.add(index, dataSize, dataAddr, origAddr);
            }
        }
        
        public void endUpdate(Segment segTarget) throws IOException
        {
            segTarget.force();
            _batch.setTargetSegment(segTarget);
            _batch.setLWMark(_dataArray.getLWMark());
            _log.info("compaction batch " + _batch.getDescriptiveId() + " hwMark=" + _batch.getLWMark());
            
            _serviceBatchQueue.add(_batch);
            _batchServiceIdCounter = 0;
            nextBatch();
        }
        
        public void clear()
        {
            _batchServiceIdCounter = 0;
            _batch.clear();
            _batch.setServiceId(_batchServiceIdCounter++);
        }
    }
    
    static class BufferedSegment extends MemorySegment {
        private ByteBuffer _byteBuffer = null;
        
        public BufferedSegment(Segment segment, ByteBuffer buffer) throws IOException {
            super(segment.getSegmentId(), segment.getSegmentFile(), segment.getInitialSizeMB(), segment.getMode());
            this._byteBuffer = buffer;
            this.init();
        }
        
        @Override
        protected void init() throws IOException {
            if(_byteBuffer == null) return;
            super.init();
        }
        
        @Override
        protected ByteBuffer initByteBuffer() {
            _byteBuffer.clear();
            return _byteBuffer;
        }
    }
}
