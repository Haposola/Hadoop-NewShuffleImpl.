package org.apache.hadoop.mapreduce.newShuffleImpl;

/**
 * Created by haposola on 16-6-8.
 *
 */

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.serializer.Deserializer;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.mapreduce.*;
import org.apache.hadoop.mapreduce.task.TaskInputOutputContextImpl;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Vector;

/**
 * The context passed to the {@link Reducer}.
 * @param <KEYIN> the class of the input keys
 * @param <VALUEIN> the class of the input values
 * @param <KEYOUT> the class of the output keys
 * @param <VALUEOUT> the class of the output values
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class NewReduceContextImpl<KEYIN,VALUEIN,KEYOUT,VALUEOUT>
        extends TaskInputOutputContextImpl<KEYIN,VALUEIN,KEYOUT,VALUEOUT>
        implements ReduceContext<KEYIN, VALUEIN, KEYOUT, VALUEOUT> {

    KEYIN key; VALUEIN current; boolean isFastStart;
    ValueIterable iterable;
    KEYOUT pKey;VALUEOUT pVal;

    public NewReduceContextImpl(
            Configuration conf,TaskAttemptID taskid,
            RecordWriter<KEYOUT,VALUEOUT> output, OutputCommitter committer,
            StatusReporter reporter, KEYIN key,Vector input
    ){
        super(conf,taskid,output,committer,reporter);
        this.key=key;
        this.iterable=new ValueIterable(input);
        this.isFastStart=true;
    }

    public NewReduceContextImpl(
            Configuration conf,TaskAttemptID taskid,
            RecordWriter<KEYOUT,VALUEOUT> output, OutputCommitter committer,
            StatusReporter reporter,KEYIN key, File input, Class iv
    ){
        super(conf,taskid,output,committer,reporter);
        this.key=key;
        this.iterable=new ValueIterable(input, iv);
        this.isFastStart=false;
    }

    @Override
    public boolean nextKey() throws IOException, InterruptedException {
        return false;
    }

    @Override
    public Iterable<VALUEIN> getValues() throws IOException, InterruptedException {
        return iterable;
    }

    @Override
    public boolean nextKeyValue() throws IOException, InterruptedException {
        return false;
    }

    @Override
    public KEYIN getCurrentKey() throws IOException, InterruptedException {
        return key;
    }

    @Override
    public void write(KEYOUT key, VALUEOUT value)throws IOException,InterruptedException{
        if(isFastStart){
            this.pKey=key;this.pVal=value;
        }else{
            super.write(key, value);
        }
    }
    public void writeFinal()throws IOException,InterruptedException{
        super.write(pKey,pVal);
    }
    @Override
    public VALUEIN getCurrentValue() throws IOException, InterruptedException {
        return current;
    }

    interface ValueIterator extends Iterator{}

    class PartialValueIterator implements ValueIterator{
        KEYIN key;
        Vector<VALUEIN> input;
        public PartialValueIterator(Vector<VALUEIN> input){
            this.input=input;
        }

        @Override
        public boolean hasNext() {
            return !input.isEmpty();
        }

        @Override
        public VALUEIN next() {

            while(!input.isEmpty() );//TODO : notice that when mapAllFinished, ReduceThread will spin here;
            VALUEIN tmp=input.remove(0);
            current=tmp;
            return tmp;
        }
    }

    class NormalValueIterator implements ValueIterator{
        File input;
        Deserializer<VALUEIN> idv;
        FileInputStream fis;
        public NormalValueIterator(File input, Class iv) {
            try {
                this.input = input;
                idv = new SerializationFactory(conf).getDeserializer(iv);
                fis=new FileInputStream(input);
                idv.open(fis);
            }catch(IOException e){
                e.printStackTrace();
            }
        }

        @Override
        public boolean hasNext() {
            boolean hasnext=false;
            try {
                hasnext =fis.available()>0;
            }catch (IOException e){
                e.printStackTrace();
            }
            return hasnext;
        }

        @Override
        public VALUEIN next() {
            VALUEIN tmp=null;
            try {
                tmp=idv.deserialize(tmp);
            }catch (Exception e){
                e.printStackTrace();
            }
            return tmp;
        }
    }

    class ValueIterable implements Iterable<VALUEIN> {
        ValueIterator iterator;
        public ValueIterable(Vector input){
            this.iterator=new PartialValueIterator(input);
        }
        public ValueIterable(File input,Class iv){
            this.iterator=new NormalValueIterator(input,iv);
        }
        @Override
        public Iterator<VALUEIN> iterator() {
                return iterator;
            }
    }
}
