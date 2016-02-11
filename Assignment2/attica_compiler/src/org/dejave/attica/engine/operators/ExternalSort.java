/*
 * Created on Jan 18, 2004 by sviglas
 *
 * Modified on Dec 24, 2008 by sviglas
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
 * ExternalSort: Your implementation of sorting.
 *
 * @author sviglas
 */
public class ExternalSort extends UnaryOperator {
    
    /** The storage manager for this operator. */
    private StorageManager sm;

    /** The name of the temporary file for the input*/
    private String inputFile;

    /** The name of the temporary file for the output. */
    private String outputFile;
    
    /** The manager that undertakes output relation I/O. */
    private RelationIOManager outputMan;
    
    /** The slots that act as the sort keys. */
    private int [] slots;
    
    /** Number of buffers (i.e., buffer pool pages and 
     * output files). */
    private int buffers;

    /** Iterator over the output file. */
    private Iterator<Tuple> outputTuples;

    /** Reusable tuple list for returns. */
    private List<Tuple> returnList;
    
    /**
     * Constructs a new external sort operator.
     * 
     * @param operator the input operator.
     * @param sm the storage manager.
     * @param slots the indexes of the sort keys.
     * @param buffers the number of buffers (i.e., run files) to be
     * used for the sort.
     * @throws EngineException thrown whenever the sort operator
     * cannot be properly initialized.
     */
    public ExternalSort(Operator operator, StorageManager sm,
                        int [] slots, int buffers) 
    throws EngineException {
        
        super(operator);
        this.sm = sm;
        this.slots = slots;
        this.buffers = buffers;
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
        ////////////////////////////////////////////
        //
        // initialise the temporary files here
        // make sure you throw the right exception
        // in the event of an error
        //
        // for the time being, the only file we
        // know of is the output file
        //
        ////////////////////////////////////////////

        //Give names to input and output files
        inputFile = FileUtil.createTempFileName();
        outputFile = FileUtil.createTempFileName();
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
    
    //Swap two tuples

    private void swap(int i, int j, List<Page> pages){
        
        int total_tuples = pages.get(0).getNumberOfTuples();
        
        int page_i = i / total_tuples;
        int page_j = j / total_tuples;
        int row_i = i % total_tuples;
        int row_j = j % total_tuples;
        
        Tuple tuple_i = find_tuple(i, pages);
        Tuple tuple_j = find_tuple(j, pages);

        pages.get(page_i).setTuple(row_i, tuple_j);
        pages.get(page_j).setTuple(row_j, tuple_i);
    }
    
    //Find the tuple from the page list by its index

    private Tuple find_tuple(int i, List<Page> pages){
        
        int total_tuples = pages.get(0).getNumberOfTuples();
        int page = i / total_tuples;
        int row = i % total_tuples;
        
        return pages.get(page).retrieveTuple(row);
    }
    
    //Quicksort which is used as in memory sort

    private void Qsort(int left, int right, List<Page> pages){
        
        int i = left;
        int j = right;
        Tuple mid = find_tuple((left + right) / 2, pages);
        
        while (i <= j){
            while (compare(find_tuple(i, pages), mid) < 0){
                i++;
            }
            while (compare(find_tuple(j, pages), mid) > 0){
                j--;
            }
            if (i <= j){
                swap(i, j, pages);
                i++; j--;
            }
        }
        if (i < right){
            Qsort(i, right, pages);
        }
        if (left < j){
            Qsort(left, j, pages);
        }
    }
    
    //Sort the pages and store the result in one file

    private void page_sort(List<Page> pages, List<String> file_names) throws StorageManagerException, EngineException{
        
        int total_tuples = 0;
        int total_pages = pages.size();
        
        for (int i = 0; i < total_pages; i++){
            total_tuples = total_tuples + pages.get(i).getNumberOfTuples();
        }
        
        Qsort(0, total_tuples - 1, pages);

        String tempfile = FileUtil.createTempFileName();
        sm.createFile(tempfile);
        RelationIOManager man = new RelationIOManager(sm, getOutputRelation(), tempfile);
        
        //Write the sorted tuples to the new file.
        for (int i = 0; i < pages.size(); i++){
            Page page = pages.get(i);
            Iterator<Tuple> tuple = page.iterator();
            while (tuple.hasNext()){
                man.insertTuple(tuple.next());
            }
        }
        
        //Add this new file to the file list
        file_names.add(tempfile);
    }
    
    //Find the minimum tuple among B(buffers - 1) tuples

    private int find_min(Tuple[] tuples){
        
        int location = -1;
        int i = 0;
        
        //Set the first valid tuple as minimum tuple
        while (i < tuples.length ){
            if (tuples[i] != null){
                location = i;
                break;
            }
            i++;
        }
        
        //Find the any 'smaller' tuple
        for (;i < tuples.length;i++){
            if (tuples[i] == null){
                continue;
            }
            if (compare(tuples[location], tuples[i]) > 0){
                location = i;
            }
        }
        
        return location;
    }
    
    //N-way merge sort(N = buffers - 1) tuple by tuple
    
    private List<String> file_merge(List<String> file_names) throws IOException, StorageManagerException, EngineException{
        
        int merged_files = 0;
        int i = 0;
        int location = -1;
        
        List<String> new_file = new ArrayList<String>();
        Tuple[] current_tuple = new Tuple[buffers - 1];
        RelationIOManager[] riom = new RelationIOManager[buffers - 1];
        @SuppressWarnings("unchecked")
        Iterator<Tuple>[] tuple_iterators = (Iterator<Tuple>[]) new Iterator<?>[buffers - 1];
        
        while (merged_files < file_names.size()){

            //Initialization
            for (i = 0; i < riom.length; i++){
                current_tuple[i] = null;
                riom[i] = null;
                tuple_iterators[i] = null;
            }
            
            //Link the relationiomanager with file
            for (i = 0; i < riom.length; i++){
                riom[i] = new RelationIOManager(sm,getOutputRelation(),file_names.get(merged_files));
                merged_files++;
                if (merged_files >= file_names.size()){
                    break;
                }
            }
            
            //Set the tuple iterator for every relationiomanager/file
            for (i = 0; i < riom.length; i++){
                if (riom[i] == null){
                    break;
                }
                tuple_iterators[i] = riom[i].tuples().iterator();
                current_tuple[i] = tuple_iterators[i].next();
            }
            
            //Create a new file to store the merged result 
            String temp_file = FileUtil.createTempFileName();
            sm.createFile(temp_file);
            outputMan = new RelationIOManager(sm, getOutputRelation(), temp_file);
            new_file.add(temp_file);

            //Merge the files tuple by tuple
            while (true){
                location = find_min(current_tuple);
                if (location < 0){
                    break;
                }
                outputMan.insertTuple(current_tuple[location]);
                if (tuple_iterators[location].hasNext()){
                    current_tuple[location] = tuple_iterators[location].next();
                }
                else{
                    current_tuple[location] = null;
                }
            }
        }
        
        //Delete the old files
        for (String file_name : file_names){
            sm.deleteFile(file_name);
        }
        
        return new_file;
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
            // in a temporary file and sort the file
            //
            ////////////////////////////////////////////
            
            //Initialization
            sm.createFile(inputFile);
            RelationIOManager riom = new RelationIOManager(sm, getOutputRelation(), inputFile);
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
            
            List<String> files = new ArrayList<String>();
            List<Page> pages = new ArrayList<Page>();
            
            //First run, sort in the pages
            for (Page page : riom.pages()){
                pages.add(page);
                if (pages.size() == buffers){
                    page_sort(pages, files);
                    pages.clear();
                }
            }
            if (pages.size() > 0){
                page_sort(pages,files);
                pages.clear();
            }
            sm.deleteFile(inputFile);
            
            ////////////////////////////////////////////
            //
            // YOUR CODE GOES HERE
            //
            ////////////////////////////////////////////
            
            //Remaining runs, merge sort
            while (files.size() > 1){
                files = file_merge(files);
            }

            System.in.read();
            
            ////////////////////////////////////////////
            //
            // the output should reside in the output file
            //
            ////////////////////////////////////////////
            
            outputFile = files.get(0);
            outputMan = new RelationIOManager(sm, getOutputRelation(), outputFile);
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
            ////////////////////////////////////////////
            //
            // make sure you delete the intermediate
            // files after sorting is done
            //
            ////////////////////////////////////////////
            
            ////////////////////////////////////////////
            //
            // right now, only the output file is 
            // deleted
            //
            ////////////////////////////////////////////
            sm.deleteFile(outputFile);
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
            else returnList.add(new EndOfStreamTuple());
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

} // ExternalSort
