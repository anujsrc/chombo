/*
 * chombo: Hadoop Map Reduce utility
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */


package org.chombo.mr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.chombo.util.Pair;
import org.chombo.util.Tuple;
import org.chombo.util.Utility;

/**
 * Counts missing field values colun wise or row wise
 * @author pranab
 *
 */
public class MissingValueCounter extends Configured implements Tool {

	@Override
	public int run(String[] args) throws Exception {
        Job job = new Job(getConf());
        String jobName = "MR for missing value counting for all fields ";
        job.setJobName(jobName);
        
        job.setJarByClass(MissingValueCounter.class);
        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        Utility.setConfiguration(job.getConfiguration(), "chombo");
        job.setMapperClass(MissingValueCounter.CounterMapper.class);
        if (job.getConfiguration().get("mvc.couting.dimension", "column").equals("column")) { 
            job.setCombinerClass(MissingValueCounter.CounterCombiner.class);
        }
    	job.setReducerClass(MissingValueCounter.CounterReducer.class);
        
        job.setMapOutputKeyClass(Tuple.class);
        job.setMapOutputValueClass(Tuple.class);

        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);

        int numReducer = job.getConfiguration().getInt("mvc.num.reducer", -1);
        numReducer = -1 == numReducer ? job.getConfiguration().getInt("num.reducer", 1) : numReducer;
        job.setNumReduceTasks(numReducer);

        int status =  job.waitForCompletion(true) ? 0 : 1;
        return status;
	}

	public static class CounterMapper extends Mapper<LongWritable, Text, Tuple, Tuple> {
		private Tuple outKey = new Tuple();
		private Tuple outVal = new Tuple();
		private int[]  attributes;
        private String[] items;
        private String fieldDelimRegex;
        private int[] idOrdinals;
        private String dimension;

        /* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Mapper#setup(org.apache.hadoop.mapreduce.Mapper.Context)
         */
        protected void setup(Context context) throws IOException, InterruptedException {
        	Configuration config = context.getConfiguration();
        	fieldDelimRegex = Utility.getFieldDelimiter(config, "mvc.field.delim.regex", "field.delim.regex", ",");
        	idOrdinals = Utility.intArrayFromString(config.get("mvc.id.field.ordinals"));
        	dimension = config.get("mvc.counting.dimension", "column");
        }    
        
        @Override
        protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
            items  =  value.toString().split(fieldDelimRegex);
            
            if (dimension.equals("row")) {
            	
            } else {
            	int i = idOrdinals.length;
            	for ( ; i < items.length; ++i) {
            		if (items[i].isEmpty()) {
            			outKey.initialize();
            			outKey.add(i);
            			outVal.initialize();
            			outVal.add(1);
                    	context.write(outKey, outVal);
            		}
            	}
            }
        }        
	}	

	/**
	 * @author pranab
	 *
	 */
	public static class CounterCombiner extends Reducer<Tuple, Tuple, Tuple, Tuple> {
		private Tuple outVal = new Tuple();
		private int count;
		
        /* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Reducer#reduce(KEYIN, java.lang.Iterable, org.apache.hadoop.mapreduce.Reducer.Context)
         */
        protected void reduce(Tuple key, Iterable<Tuple> values, Context context)
        		throws IOException, InterruptedException {
        	outVal.initialize();
        	count = 0;
    		for (Tuple val : values) {
    			count += val.getInt(0);
    		}
    		outVal.add(count);
        	context.write(key, outVal);
        }	
	}
	
	/**
	* @author pranab
  	*
  	*/
	public static class CounterReducer extends Reducer<Tuple, Tuple, NullWritable, Text> {
		protected Text outVal = new Text();
		protected StringBuilder stBld =  new StringBuilder();;
		protected String fieldDelim;
        private String dimension;
        private int count;
        private List<MissingColumnCounter> colCounters = new ArrayList<MissingColumnCounter>();
        
		/* (non-Javadoc)
		 * @see org.apache.hadoop.mapreduce.Reducer#setup(org.apache.hadoop.mapreduce.Reducer.Context)
		 */
		protected void setup(Context context) throws IOException, InterruptedException {
        	Configuration config = context.getConfiguration();
        	fieldDelim = config.get("field.delim.out", ",");
        	dimension = config.get("mvc.counting.dimension", "column");
		}
		
		@Override
		protected void cleanup(Context context) throws IOException, InterruptedException {
			Collections.sort(colCounters);
			for (MissingColumnCounter colCnt : colCounters) {
				outVal.set("" + colCnt.getLeft() + fieldDelim + colCnt.getRight());
				context.write(NullWritable.get(), outVal);
			}
			super.cleanup(context);
		}
		
		/* (non-Javadoc)
    	 * @see org.apache.hadoop.mapreduce.Reducer#reduce(KEYIN, java.lang.Iterable, org.apache.hadoop.mapreduce.Reducer.Context)
    	 */
    	protected void reduce(Tuple key, Iterable<Tuple> values, Context context)
        	throws IOException, InterruptedException {
            if (dimension.equals("row")) {
            	
            } else {
            	count = 0;
        		for (Tuple val : values) {
        			count += val.getInt(0);
        		}
        		MissingColumnCounter colCnt = new MissingColumnCounter(key.getInt(0), count);
        		colCounters.add(colCnt);
            }
    	}		
	}	
	
	/**
	 * @author pranab
	 *
	 */
	private static class MissingColumnCounter extends Pair<Integer, Integer>  implements Comparable {
		public MissingColumnCounter(Integer ordinal, Integer count) {
			super(ordinal, count);
		}
		
		@Override
		public int compareTo(Object other) {
			MissingColumnCounter that = (MissingColumnCounter)other;
			int thisCount = this.getRight();
			int thatCount = that.getRight();
			return thatCount - thisCount;
		}
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		int exitCode = ToolRunner.run(new MissingValueCounter(), args);
		System.exit(exitCode);
	}
	
}
