/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ldbc.snb.datagen.hadoop;

import ldbc.snb.datagen.dictionary.Dictionaries;
import ldbc.snb.datagen.generator.DatagenParams;
import ldbc.snb.datagen.generator.LDBCDatagen;
import ldbc.snb.datagen.generator.PersonActivityGenerator;
import ldbc.snb.datagen.objects.Knows;
import ldbc.snb.datagen.objects.Person;
import ldbc.snb.datagen.serializer.PersonActivitySerializer;
import ldbc.snb.datagen.serializer.UpdateEventSerializer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 *
 * @author aprat
 */
public class HadoopPersonActivityGenerator {

    public static class HadoopPersonActivityGeneratorReducer  extends Reducer<BlockKey, Person, LongWritable, Person> {

        private int reducerId;                          /** The id of the reducer.**/
        private PersonActivitySerializer personActivitySerializer_;
        private PersonActivityGenerator personActivityGenerator_;
        private UpdateEventSerializer updateSerializer_;
        private OutputStream personFactors_;
        private OutputStream activityFactors_;
        private OutputStream friends_;
        private FileSystem fs_;

        protected void setup(Context context) {
            System.out.println("Setting up reducer for person activity generation");
            Configuration conf = context.getConfiguration();
            reducerId = context.getTaskAttemptID().getTaskID().getId();
            LDBCDatagen.init(conf);
            try {
                personActivitySerializer_ = (PersonActivitySerializer) Class.forName(conf.get("ldbc.snb.datagen.serializer.personActivitySerializer")).newInstance();
                personActivitySerializer_.initialize(conf,reducerId);
                if(DatagenParams.updateStreams) {
                    updateSerializer_ = new UpdateEventSerializer(conf, DatagenParams.hadoopDir + "/temp_updateStream_forum_" + reducerId, reducerId, DatagenParams.numUpdatePartitions);
                }
                personActivityGenerator_ = new PersonActivityGenerator(personActivitySerializer_, updateSerializer_);

                fs_ = FileSystem.get(context.getConfiguration());
                personFactors_ = fs_.create(new Path(DatagenParams.hadoopDir+"/"+ "m" + reducerId + DatagenParams.PERSON_COUNTS_FILE));
                activityFactors_ = fs_.create(new Path(DatagenParams.hadoopDir+"/"+ "m" + reducerId + DatagenParams.ACTIVITY_FILE));
                friends_ = fs_.create(new Path(DatagenParams.hadoopDir+"/"+ "m0friendList" + reducerId +".csv"));

            } catch( Exception e ) {
                System.err.println(e.getMessage());
                e.printStackTrace();
            }
        }

        @Override
        public void reduce(BlockKey key, Iterable<Person> valueSet,Context context)
                throws IOException, InterruptedException {
            System.out.println("Reducing block "+key.block);
            ArrayList<Person> persons = new ArrayList<Person>();
            for( Person p : valueSet ) {
                persons.add(new Person(p));

                StringBuilder strbuf = new StringBuilder();
                strbuf.append(p.accountId());
                for( Knows k : p.knows() ) {
                    strbuf.append(",");
                    strbuf.append(k.to().accountId());
                    if( k.creationDate() > Dictionaries.dates.getUpdateThreshold() && DatagenParams.updateStreams ) {
                        updateSerializer_.export(p,k);
                    }
                }
                if( DatagenParams.updateStreams ) {
                    updateSerializer_.changePartition();
                }
                strbuf.append("\n");
                friends_.write(strbuf.toString().getBytes("UTF8"));
            }
            System.out.println("Starting generation of block: "+key.block);
            personActivityGenerator_.generateActivityForBlock((int)key.block, persons, context );
            System.out.println("Writing person factors for block: "+key.block);
            personActivityGenerator_.writePersonFactors(personFactors_);
        }
        protected void cleanup(Context context){
            try {
                System.out.println("Cleaning up");
                personActivityGenerator_.writeActivityFactors(activityFactors_);
                activityFactors_.close();
                personFactors_.close();
                friends_.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            personActivitySerializer_.close();
            if(DatagenParams.updateStreams) {
                updateSerializer_.close();
            }
        }
    }


    private Configuration conf;

    public HadoopPersonActivityGenerator(Configuration conf) {
        this.conf = conf;
    }

    public void run( String inputFileName ) throws AssertionError, Exception {

        FileSystem fs = FileSystem.get(conf);

        System.out.println("RANKING");
        String rankedFileName = conf.get("ldbc.snb.datagen.serializer.hadoopDir") + "/ranked";
        HadoopFileRanker hadoopFileRanker = new HadoopFileRanker( conf, TupleKey.class, Person.class, null );
        hadoopFileRanker.run(inputFileName,rankedFileName);

        System.out.println("GENERATING");
        int numThreads = Integer.parseInt(conf.get("ldbc.snb.datagen.generator.numThreads"));
        Job job = Job.getInstance(conf, "Person Activity Generator/Serializer");
        job.setMapOutputKeyClass(BlockKey.class);
        job.setMapOutputValueClass(Person.class);
        job.setOutputKeyClass(LongWritable.class);
        job.setOutputValueClass(Person.class);
        job.setJarByClass(HadoopBlockMapper.class);
        job.setMapperClass(HadoopBlockMapper.class);
        job.setReducerClass(HadoopPersonActivityGeneratorReducer.class);
        job.setNumReduceTasks(numThreads);
        job.setInputFormatClass(SequenceFileInputFormat.class);
        job.setOutputFormatClass(SequenceFileOutputFormat.class);

        job.setSortComparatorClass(BlockKeyComparator.class);
        job.setGroupingComparatorClass(BlockKeyGroupComparator.class);
        job.setPartitionerClass(HadoopBlockPartitioner.class);

        /** PROFILING OPTIONS **/
        //job.setProfileEnabled(true);
        //job.setProfileParams("-agentlib:hprof=cpu=samples,heap=sites,depth=4,thread=y,format=b,file=%s");
        //job.setProfileTaskRange(true,"0-1");
        //job.setProfileTaskRange(false,"0-1");
        /****/

        FileInputFormat.setInputPaths(job, new Path(rankedFileName));
        FileOutputFormat.setOutputPath(job, new Path(conf.get("ldbc.snb.datagen.serializer.hadoopDir")+"/aux"));
        long start = System.currentTimeMillis();
        try {
            if (!job.waitForCompletion(true)) {
                throw new Exception();
            }
        } catch (AssertionError e)  {
            throw e;
        }
        System.out.println("Real time to generate activity: "+(System.currentTimeMillis() - start)/1000.0f);

        try{
            fs.delete(new Path(rankedFileName), true);
            fs.delete(new Path(conf.get("ldbc.snb.datagen.serializer.hadoopDir")+"/aux"),true);
        } catch(IOException e) {
            System.err.println(e.getMessage());
            e.printStackTrace();
        }
    }

}
