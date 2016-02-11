/*
 * Created on Jan 12, 2015 by sviglas
 *
 * This is part of the attica project.  Any subsequent modification
 * of the file should retain this disclaimer.
 * 
 * University of Edinburgh, School of Informatics
 */
package org.dejave.attica.engine.operators;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;

import org.dejave.attica.model.Relation;
import org.dejave.attica.storage.Page;
import org.dejave.attica.storage.Tuple;
import org.dejave.attica.storage.RelationIOManager;
import org.dejave.attica.storage.StorageManager;
import org.dejave.attica.storage.StorageManagerException;
import org.dejave.attica.storage.FileUtil;

/**
 * HashGroup: Your implementation of hash-based grouping.
 *
 * @author sviglas
 */
public class HashGroup extends UnaryOperator {
    
    /** The storage manager for this operator. */
    private StorageManager sm;
    
    /** The name of the temporary file for the output. */
    private String outputFile;
	
    /** The manager that undertakes output relation I/O. */
    private RelationIOManager outputMan;
	
    /** The slots that act as the group keys. */
    private int [] slots;
	
    /** Number of buffer pool pages to use. */
    private int buffers;

    /** Iterator over the output file. */
    private Iterator<Tuple> outputTuples;

    /** Reusable tuple list for returns. */
    private List<Tuple> returnList;

    /** The list of partition files/managers. */
    private List<RelationIOManager> partitionFiles;

    /** The prefix name for all partition files. */
    private String partitionFilePrefix;
    
    /** The current partition being scanned for output. */
    private int currentPartition;
    
    /**
     * Constructs a new hash grouping operator.
     * 
     * @param operator the input operator.
     * @param sm the storage manager.
     * @param slots the indexes of the grouping keys.
     * @param buffers the number of buffers to be used for grouping.
     * @throws EngineException thrown whenever the grouping operator
     * cannot be properly initialized.
     */
    public HashGroup(Operator operator, StorageManager sm,
		     int [] slots, int buffers) 
	throws EngineException {
        
        super(operator);
        this.sm = sm;
        this.slots = slots;
        this.buffers = buffers;
        currentPartition = 0;
        partitionFiles = new ArrayList<RelationIOManager>();
        try {
            // create the temporary output files
            initTempFiles();
        }
        catch (StorageManagerException sme) {
            throw new EngineException("Could not instantiate external sort",
                                      sme);
        }
    } // ExternalSort()
	

    /**
     * Initialises the temporary files, according to the number
     * of buffers.
     * 
     * @throws StorageManagerException thrown whenever the temporary
     * files cannot be initialised.
     */
    protected void initTempFiles() throws StorageManagerException {
	try {
	    partitionFilePrefix = FileUtil.createTempFileName();
	    ////////////////////////////////////////////
	    //
	    // allocate any other files you see fit here
	    //
	    ////////////////////////////////////////////
	}
	catch (Exception e) {
	    e.printStackTrace(System.err);
	    throw new StorageManagerException("Could not instantiate temporary "
					      + "files for hash grouping", e);
	}
    } // initTempFiles()
    
    //Compare two tuples by their slots
    
    private int compare(Tuple t1, Tuple t2){
    	
    	int x = 0;
    	
    	for (int i = 0; i < slots.length; i++){
    		x = t1.getValue(slots[i]).compareTo(t2.getValue(slots[i]));
    		if (x != 0){
    			return x;
    		}
    	}
    	return 0;
    }
    
    //Find the tuple from pages by its index
    
    private Tuple find_tuple(int i, RelationIOManager riom, int total_tuples) throws ArrayIndexOutOfBoundsException, IOException, StorageManagerException{
    	
    	int page = i / total_tuples;
    	int row = i % total_tuples;
    	int count = 0;
    	
    	for (Page p : riom.pages()){
    		if (count == page){
    			return p.retrieveTuple(row);
    		}
    		count++;
    	}
		return null;
    }
    
    //Swap two tuples
    
    private void swap(int i, int j, RelationIOManager riom, int total_tuples) throws ArrayIndexOutOfBoundsException, IOException, StorageManagerException{
    	
    	int count = 0;
    	Tuple tuple_i = null;
    	Tuple tuple_j = null;
    	Page i_page = null;
    	Page j_page = null;
    	int page_i = i / total_tuples;
    	int page_j = j / total_tuples;
    	int row_i = i % total_tuples;
    	int row_j = j % total_tuples;
    	
    	for (Page page : riom.pages()){
    		if (count == page_i){
    			i_page = page;
    		}
    		if (count == page_j){
    			j_page = page;
    		}
            count++;
    	}
    	tuple_i = i_page.retrieveTuple(row_i);
    	tuple_j = j_page.retrieveTuple(row_j);
    	i_page.setTuple(row_i, tuple_j);
    	j_page.setTuple(row_j, tuple_i);
    }
    
    //Quicksort which is used as in memory sort
    
    private void Qsort(int left, int right, RelationIOManager riom, int total_tuples) throws ArrayIndexOutOfBoundsException, IOException, StorageManagerException{
    	
    	int i = left;
    	int j = right;
    	Tuple mid = find_tuple((left + right) / 2, riom, total_tuples);
    	
    	while (i <= j){
    		while (compare(find_tuple(i, riom, total_tuples), mid) < 0){
    			i++;
    		}
    		while (compare(find_tuple(j, riom, total_tuples), mid) > 0){
    			j--;
    		}
    		if (i <= j){
    			swap(i, j, riom, total_tuples);
    			i++;
    			j--;
    		}
    	}
    	if (i < right){
    		Qsort(i, right ,riom, total_tuples);
    	}
    	if (left < j){
    		Qsort(left, j, riom, total_tuples);
    	}
    }
    
    //Sort every partitions after hash group
    
    private void hash_group(String partition_file) throws EngineException, IOException, StorageManagerException{
    	
    	int pages = 0;
    	int tuples = 0;
    	int total_tuples = 0;
    	
    	RelationIOManager riom = new RelationIOManager(sm, getOutputRelation(), partition_file);
    	for (Page page : riom.pages()){
    		if (pages == 0){
    			total_tuples = page.getNumberOfTuples();
    		}
    		pages++;
    		tuples = tuples + page.getNumberOfTuples();
    	}
    	if (pages == 0){
    		return;
    	}
    	Qsort(0, tuples - 1, riom, total_tuples);
    }
    
    /**
     * Sets up this external sort operator.
     * 
     * @throws EngineException thrown whenever there is something wrong with
     * setting this operator up
     */
    public void setup() throws EngineException {
        returnList = new ArrayList<Tuple>();
        try {
            ////////////////////////////////////////////
            //
            // this is a blocking operator -- store the input
        	// and then generate the partition files
            //
            ////////////////////////////////////////////
            
        	int partition_files;
        	int hash_sum;
        	
        	//Initialization
        	String input_file = FileUtil.createTempFileName();
        	sm.createFile(input_file);
        	RelationIOManager riom = new RelationIOManager(sm, getOutputRelation(), input_file);
        	
        	boolean flag = false;
        	while (!flag){
        		Tuple tuple = getInputOperator().getNext();
        		if (tuple != null){
        			flag = (tuple instanceof EndOfStreamTuple);
        			if (!flag){
        				riom.insertTuple(tuple);
        			}
        		}
        	}
    
            ////////////////////////////////////////////
            //
            // YOUR CODE GOES HERE
            //
            ////////////////////////////////////////////
            
        	//Number of partitions
        	partition_files = (FileUtil.getNumberOfPages(riom.getFileName()) / buffers + 1) * 2;
			
        	List<String> files = new ArrayList<String>();
        	RelationIOManager[] rioms = new RelationIOManager[partition_files];
        	
        	//Create temporary file and link it with a relationiomanager
        	for (int i = 0; i < partition_files ; i++){
        		String temp_file = FileUtil.createTempFileName();
        		sm.createFile(temp_file);
        		files.add(temp_file);
        		rioms[i] = new RelationIOManager(sm, getOutputRelation(), temp_file);
        	}
        	
        	//Hash group every tuple by its hash code
        	for (Tuple tuple : riom.tuples()){
        		hash_sum = 0;
        		for (int i = 0; i < slots.length; i++){
        			hash_sum = hash_sum + tuple.getValue(slots[i]).hashCode() * 29;
        		}
        		hash_sum = hash_sum % partition_files;
        		while (hash_sum < 0){
        			hash_sum = hash_sum + partition_files;
        		}
        		rioms[hash_sum].insertTuple(tuple);
        	}

        	//Sort the grouped files
        	for (int i = 0; i < partition_files; i++){
        		hash_group(files.get(i));
        	}

        	//Add the sorted files to the result
        	for (int i = 0; i < rioms.length; i++){
        		partitionFiles.add(rioms[i]);
        	}
        	
            ////////////////////////////////////////////
            //
            // the output should reside in multiple
        	// output files; instantiate the first manager
        	// to the first such file
            //
            ////////////////////////////////////////////
            currentPartition = 0;
            outputMan = partitionFiles.get(currentPartition);
            outputTuples = outputMan.tuples().iterator();
        }
        catch (Exception sme) {
            throw new EngineException("Could not store and sort"
                                      + "intermediate files.", sme);
        }
    } // setup()

    
    /**
     * Cleanup after the sort.
     * 
     * @throws EngineException whenever the operator cannot clean up
     * after itself.
     */
    public void cleanup () throws EngineException {
        try {
            for (int i = 0; i < partitionFiles.size(); ++i) {
		sm.deleteFile(generatePartitionFileName(i));
	    }
        }
        catch (StorageManagerException sme) {
            throw new EngineException("Could not clean up final output.", sme);
        }
    } // cleanup()

    
    /**
     * The inner method to retrieve tuples.
     * 
     * @return the newly retrieved tuples.
     * @throws EngineException thrown whenever the next iteration is not 
     * possible.
     */    
    protected List<Tuple> innerGetNext () throws EngineException {
        try {
            returnList.clear();
            if (outputTuples.hasNext()) returnList.add(outputTuples.next());
            else{
            	while (++currentPartition < partitionFiles.size()) {
            		outputMan = partitionFiles.get(currentPartition);
            		outputTuples = outputMan.tuples().iterator();
            		if (outputTuples.hasNext()) {
            			returnList.add(outputTuples.next());
            			return returnList;
            		}
            	}
            	returnList.add(new EndOfStreamTuple());
            }
            return returnList;
        }
        catch (Exception sme) {
            throw new EngineException("Could not read tuples " +
                                      "from intermediate file.", sme);
        }
    } // innerGetNext()


    /**
     * Operator class abstract interface -- never called.
     */
    protected List<Tuple> innerProcessTuple(Tuple tuple, int inOp)
	throws EngineException {
        return new ArrayList<Tuple>();
    } // innerProcessTuple()

    
    /**
     * Operator class abstract interface -- sets the ouput relation of
     * this sort operator.
     * 
     * @return this operator's output relation.
     * @throws EngineException whenever the output relation of this
     * operator cannot be set.
     */
    protected Relation setOutputRelation() throws EngineException {
        return new Relation(getInputOperator().getOutputRelation());
    } // setOutputRelation()

    /**
     * Helper method to generate a partition file name.
     *
     * @param i the partition number for which it generated a file
     * name.
     * @return the partition file name corresponding to the given
     * partition number.
     */
    protected String generatePartitionFileName(int i) {
	StringBuilder sb = new StringBuilder();
	sb.append(partitionFilePrefix).append(".").append(i);
	return sb.toString();
    } // generatePartitionFileName()

} // HashGroup
