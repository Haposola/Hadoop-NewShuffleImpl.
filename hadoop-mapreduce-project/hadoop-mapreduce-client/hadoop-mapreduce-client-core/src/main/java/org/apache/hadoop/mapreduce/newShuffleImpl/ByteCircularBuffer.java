package org.apache.hadoop.mapreduce.newShuffleImpl;



/**
 * Created by haposola on 16-5-30.
 * We should ensure there is one producer and one consumer associated with each BCB
 * Then we could throw away the concern about the LOCK with head and tail
 * It seems that producers put data the same way.
 * But consumers get data all differently.
 * MapOutputPushThread occupies all the data between tail and head and send it, moving tail as ACK received
 * Partial Reducers deserialize values from buffer. Reads vlen, deserializes value, and moves tail.
 * SpillThread wants data to aline at disk block size, writes them to disk and moves tail.
 *TODO : So HOW?
 * I try to separate GET and CONSUME. So users can add operations between them.
 * In put function, PUT and PRODUCE are both implemented.
 * NO!!! Separate PUT and PRODUCE too.
 *TODO : This seems reasonable.
 */
public class ByteCircularBuffer {
    byte[] data;
    int size, tail, head,current;

    public ByteCircularBuffer(int size){
        this.size=size;
        this.data=new byte[size];
        tail=head=current=0;
    }
    public ByteCircularBuffer(){
        this.size=65508;
        this.data=new byte[size];
        tail=head=current=0;
    }
    public ByteCircularBuffer(byte[] buff){
        this.data=buff;
        this.size=buff.length;
        tail=head=current=0;
    }
    public int getSize(){return size;}
    public int getHead(){
        return  head;
    }
    public int getCurrent(){
        return current;
    }
    public int getTail(){
        return tail;
    }
    public void produce(int mount){
        head=(head+mount+size)%size;
    }
    public void consume(int mount){
        while(occupiedBlocks()<mount);
        tail=(tail+mount+size)%size;
    }
    public void moveHeadToCurrent(){
        head=current;
    }
    public void put(byte[] buf,int off,int len){
        while(freeBlocks()<len);//spin to wait for the consumer to move tail;
        int gap= size-current;
        if(current+len>size){
            System.arraycopy(buf,off,data,head,gap);
            len-=gap;
            off+=gap;
            current=0;
        }
        System.arraycopy(buf,off,data,current,len);
        current=(current+len+size)%size;
    }
    public void  get(byte[]buff,int off,int start,int end){
        assert (comparePos(tail,start)!=1 &&comparePos(end,head)!=1);
        int len=distanceTo(start,end);
        int gap=size-start;
        if(start+len>size){
            System.arraycopy(data,start,buff,off,gap);
            len-=gap;
            start=0;
            off+=gap;
        }
        System.arraycopy(data,start,buff,off,len);
    }

    public int occupiedBlocks(){
        return distanceTo(tail,head);
    }
    public int freeBlocks(){
        int end=(tail-1+size)%size;
        return distanceTo(current,end);
    }
    public int distanceTo(int start,int end){
        return (start<=end ? end-start :size-start+end);
    }
    int comparePos(int i,int j){
        int t=tail;
        return Integer.compare(distanceTo(t, i), distanceTo(t, j));
    }
}
