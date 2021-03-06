package test.sos;

import java.io.File;
import java.util.List;
import java.util.Random;

import krati.sos.ObjectCache;
import krati.sos.SerializableObjectCache;
import krati.store.DataCache;
import krati.store.DataCacheImpl;
import krati.util.Chronos;
import test.AbstractTest;
import test.StatsLog;
import test.protos.MemberDataGen;
import test.protos.MemberProtos;
import test.protos.MemberSerializer;

/**
 * Test SerializableObjectCache
 * 
 * @author jwu
 *
 */
public class TestObjectCache extends AbstractTest
{
    public TestObjectCache()
    {
        super(TestObjectCache.class.getName());
    }
    
    private DataCache getDataCache(File cacheDir) throws Exception
    {
        DataCache cache = new DataCacheImpl(_idStart,
                                            _idCount,
                                            cacheDir,
                                            new krati.core.segment.MemorySegmentFactory(),
                                            _segFileSizeMB);
        return cache;
    }
    
    public void testObjectCache() throws Exception
    {
        String unitTestName = getClass().getSimpleName(); 
        StatsLog.beginUnit(unitTestName);
        cleanTestOutput();
        
        File objectCacheDir = getHomeDirectory();
        DataCache dataCache = getDataCache(objectCacheDir);
        ObjectCache<MemberProtos.Member> memberCache =
            new SerializableObjectCache<MemberProtos.Member>(dataCache, new MemberSerializer());
        
        int numSeedMembers = 10000; 
        MemberProtos.MemberBook book = MemberDataGen.generateMemberBook(numSeedMembers);
        
        long scn = 0;
        int cacheSize = memberCache.getObjectIdCount();
        int objectIdStart = memberCache.getObjectIdStart();
        int objectIdEnd = objectIdStart + cacheSize;

        Chronos timer = new Chronos();
        List<MemberProtos.Member> mList = book.getMemberList();
        
        // Sequential update
        for(int i = objectIdStart; i < objectIdEnd; i++)
        {
            MemberProtos.Member m = mList.get(i%numSeedMembers);
            memberCache.set(i, m, scn++);
        }
        StatsLog.logger.info("Populate " + cacheSize + " objects in " + timer.getElapsedTime());
        
        // Persist
        memberCache.persist();
        timer.tick();
        
        // Result validation
        for(int i = objectIdStart; i < objectIdEnd; i++)
        {
            MemberProtos.Member m = mList.get(i%numSeedMembers);
            assertTrue("Member " + m.getMemberId(), memberCache.get(i).equals(m));
        }
        StatsLog.logger.info("Validate " + cacheSize + " objects in " + timer.getElapsedTime());
        
        // Random update
        Random rand = new Random();
        for(int i = objectIdStart; i < objectIdEnd; i++)
        {
            int objectId = objectIdStart + rand.nextInt(cacheSize);
            MemberProtos.Member m = mList.get(objectId%numSeedMembers);
            memberCache.set(objectId, m, scn++);
        }
        StatsLog.logger.info("Populate " + cacheSize + " objects in " + timer.getElapsedTime());
        
        // Persist
        memberCache.persist();
        timer.tick();
        
        // Result validation
        for(int i = memberCache.getObjectIdStart(); i < cacheSize; i++)
        {
            MemberProtos.Member m = mList.get(i%mList.size());
            assertTrue("Member " + m.getMemberId(), memberCache.get(i).equals(m));
        }
        StatsLog.logger.info("Validate " + cacheSize + " objects in " + timer.getElapsedTime());
        
        cleanTestOutput();
        StatsLog.endUnit(unitTestName);
    }
}
