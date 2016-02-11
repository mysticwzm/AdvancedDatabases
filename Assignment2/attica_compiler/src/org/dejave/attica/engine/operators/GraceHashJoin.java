/*
 * Created on Feb 11, 2004 by sviglas
 *
 * Modified on Feb 17, 2009 by sviglas
 *
 * This is part of the attica project.  Any subsequent modification
 * of the file should retain this disclaimer.
 * 
 * University of Edinburgh, School of Informatics
 */
package org.dejave.attica.engine.operators;

import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.io.IOException;

import org.dejave.attica.model.Relation;
import org.dejave.attica.engine.predicates.Predicate;
import org.dejave.attica.engine.predicates.PredicateEvaluator;
import org.dejave.attica.engine.predicates.PredicateTupleInserter;
import org.dejave.attica.storage.IntermediateTupleIdentifier;
import org.dejave.attica.storage.RelationIOManager;
import org.dejave.attica.storage.StorageManager;
import org.dejave.attica.storage.StorageManagerException;
import org.dejave.attica.storage.Tuple;
import org.dejave.attica.storage.FileUtil;

/**
 * MergeJoin: Implements a merge join. The assumptions are that the
 * input is already sorted on the join attributes and the join being
 * evaluated is an equi-join.
 *
 * @author sviglas
 * 
 */
public class GraceHashJoin extends PhysicalJoin {
	
    /** The name of the temporary file for the output. */
    private String outputFile;
    
    /** The relation manager used for I/O. */
    private RelationIOManager outputMan;
    
    /** The pointer to the left sort attribute. */
    private int leftSlot;
	
    /** The pointer to the right sort attribute. */
    private int rightSlot;

    /** The number of buffers to be used for hash tables. */
    private int buffers;

    /** The iterator over the output file. */
    private Iterator<Tuple> outputTuples;

    /** Reusable output list. */
    private List<Tuple> returnList;
	
    /**
     * Constructs a new grace-hash join operator.
     * 
     * @param left the left input operator.
     * @param right the right input operator.
     * @param sm the storage manager.
     * @param leftSlot pointer to the left sort attribute.
     * @param rightSlot pointer to the right sort attribute.
     * @param buffers the number of buffers to be used for the hash tables.
     * @param predicate the predicate evaluated by this join operator.
     * @throws EngineException thrown whenever the operator cannot be
     * properly constructed.
     */
    public GraceHashJoin(Operator left, 
			 Operator right,
			 StorageManager sm,
			 int leftSlot,
			 int rightSlot,
			 int buffers,
			 Predicate predicate) 
	throws EngineException {
	
        super(left, right, sm, predicate);
        this.leftSlot = leftSlot;
        this.rightSlot = rightSlot;
	    this.buffers = buffers;
        returnList = new ArrayList<Tuple>();
        try {
            initTempFiles();
        }
        catch (StorageManagerException sme) {
            EngineException ee = new EngineException("Could not instantiate " +
                                                     "merge join");
            ee.setStackTrace(sme.getStackTrace());
            throw ee;
        }
    } // GraceHashJoin()


    /**
     * Initialise the temporary files -- if necessary.
     * 
     * @throws StorageManagerException thrown whenever the temporary
     * files cannot be initialised.
     */
    protected void initTempFiles() throws StorageManagerException {
        ////////////////////////////////////////////
        //
        // initialise the temporary files here
        // make sure you throw the right exception
        //
        ////////////////////////////////////////////
        outputFile = FileUtil.createTempFileName();
    } // initTempFiles()

    
    /**
     * Sets up this merge join operator.
     * 
     * @throws EngineException thrown whenever there is something
     * wrong with setting this operator up.
     */
    
    @Override
    protected void setup() throws EngineException {
        try {
            ////////////////////////////////////////////
            //
            // YOUR CODE GOES HERE
            //
            ////////////////////////////////////////////
            
            //Store the left input in order to get the number of pages
            //No need to store the right input
            String leftFile = FileUtil.createTempFileName();
            getStorageManager().createFile(leftFile);
            Relation leftRel = getInputOperator(LEFT).getOutputRelation();
            RelationIOManager leftMan = new RelationIOManager(getStorageManager(), leftRel, leftFile);
            boolean done = false;

            while (! done)
            {
                Tuple tuple = getInputOperator(LEFT).getNext();
                if (tuple != null)
                {
                    done = (tuple instanceof EndOfStreamTuple);
                    if (! done)
                    {
                        leftMan.insertTuple(tuple);
                    }
                }
            }

            //Initialise the partition files for the left input
            int partitionNum = (FileUtil.getNumberOfPages(leftMan.getFileName()) / buffers + 1) * 2;
            List<String> leftFiles = new ArrayList<String>();
            RelationIOManager[] leftRioms = new RelationIOManager[partitionNum];
            for (int i = 0; i < partitionNum; i++)
            {
                String leftTempFile = FileUtil.createTempFileName();
                getStorageManager().createFile(leftTempFile);
                leftFiles.add(leftTempFile);
                leftRioms[i] = new RelationIOManager(getStorageManager(), leftRel, leftTempFile);
            }

            //Apply the first hash function to the left input
            for (Tuple tuple : leftMan.tuples())
            {
                int hash = tuple.getValue(leftSlot).hashCode() * 17 % partitionNum;
                while (hash < 0)
                {
                    hash = hash + partitionNum;
                }
                leftRioms[hash].insertTuple(tuple);
            }

            //Initialise the partition files for the right input
            Relation rightRel = getInputOperator(RIGHT).getOutputRelation();
            List<String> rightFiles = new ArrayList<String>();
            RelationIOManager[] rightRioms = new RelationIOManager[partitionNum];
            for (int i = 0; i < partitionNum; i++)
            {
                String rightTempFile = FileUtil.createTempFileName();
                getStorageManager().createFile(rightTempFile);
                rightFiles.add(rightTempFile);
                rightRioms[i] = new RelationIOManager(getStorageManager(), rightRel, rightTempFile);
            }
            
            //Apply the first hash function to the right input
            done = false;
            while (! done)
            {
                Tuple tuple = getInputOperator(RIGHT).getNext();
                if (tuple != null)
                {
                    done = (tuple instanceof EndOfStreamTuple);
                    if (! done)
                    {
                        int hash = tuple.getValue(rightSlot).hashCode() * 17 % partitionNum;
                        while (hash < 0)
                        {
                            hash = hash + partitionNum;
                        }
                        rightRioms[hash].insertTuple(tuple);
                    }
                }
            }

            //Hash Join
            getStorageManager().createFile(outputFile);
            outputMan = new RelationIOManager(getStorageManager(), getOutputRelation(), outputFile);
            for (int i = 0; i < partitionNum; i++)
            {
                //Apply the second hash function the left and right input
                //HashTable used to store the result in it
                //Because the key is not a candidate key, we need to use a ArrayList to store the value
                HashMap<Integer, ArrayList<Tuple>> hashmap = new HashMap<Integer, ArrayList<Tuple>>();
                for (Tuple leftTuple : leftRioms[i].tuples())
                {
                    int hash = leftTuple.getValue(leftSlot).hashCode() * 17;
                    if (hashmap.get(hash) == null)
                    {
                    	hashmap.put(hash, new ArrayList<Tuple>());
                    	hashmap.get(hash).add(leftTuple);
                    }
                    else
                    {
                    	hashmap.get(hash).add(leftTuple);
                    }
                }
                //If the right tuple match the left tuple, combine them and add to the result
                for (Tuple rightTuple : rightRioms[i].tuples())
                {
                    int hash = rightTuple.getValue(rightSlot).hashCode() * 17;
                    if (hashmap.get(hash) != null)
                    {
                        for (Tuple leftTuple : hashmap.get(hash))
                        {
                            PredicateTupleInserter.insertTuples(leftTuple, rightTuple, getPredicate());
                            if (PredicateEvaluator.evaluate(getPredicate()))
                            {
                                Tuple newTuple = combineTuples(leftTuple, rightTuple);
                                outputMan.insertTuple(newTuple);
                            }
                        }
                    }
                }
                //Clean the hashtable
                hashmap.clear();
            }

            //Delete the temporary files
            getStorageManager().deleteFile(leftFile);
            for (int i = 0; i < partitionNum; i++)
            {
                getStorageManager().deleteFile(leftFiles.get(i));
                getStorageManager().deleteFile(rightFiles.get(i));
            }

            ////////////////////////////////////////////
            //
            // the output should reside in the output file
            //
            ////////////////////////////////////////////

            //
            // you may need to uncomment the following lines if you
            // have not already instantiated the manager -- it all
            // depends on how you have implemented the operator
            //
            //outputMan = new RelationIOManager(getStorageManager(), 
            //                                  getOutputRelation(),
            //                                  outputFile);

            // open the iterator over the output
            outputTuples = outputMan.tuples().iterator();
        }
        catch (IOException ioe) {
            throw new EngineException("Could not create page/tuple iterators.",
                                      ioe);
        }
        catch (StorageManagerException sme) {
            EngineException ee = new EngineException("Could not store " + 
                                                     "intermediate relations " +
                                                     "to files.");
            ee.setStackTrace(sme.getStackTrace());
            throw ee;
        }
    } // setup()
    
    
    /**
     * Cleans up after the join.
     * 
     * @throws EngineException whenever the operator cannot clean up
     * after itself.
     */
    @Override
    protected void cleanup() throws EngineException {
        try {
            ////////////////////////////////////////////
            //
            // make sure you delete any temporary files	    
            //
	    // the following line only deletes the
	    // generated file (you may need to comment
	    // it out if you do not create any files)
	    //
            ////////////////////////////////////////////
            
            getStorageManager().deleteFile(outputFile);
        }
        catch (StorageManagerException sme) {
            EngineException ee = new EngineException("Could not clean up " +
                                                     "final output");
            ee.setStackTrace(sme.getStackTrace());
            throw ee;
        }
    } // cleanup()

    /**
     * Inner method to propagate a tuple.
     * 
     * @return an array of resulting tuples.
     * @throws EngineException thrown whenever there is an error in
     * execution.
     */
    @Override
    protected List<Tuple> innerGetNext () throws EngineException {
        try {
            returnList.clear();
            if (outputTuples.hasNext()) returnList.add(outputTuples.next());
            else returnList.add(new EndOfStreamTuple());
            return returnList;
        }
        catch (Exception sme) {
            throw new EngineException("Could not read tuples "
                                      + "from intermediate file.", sme);
        }
    } // innerGetNext()


    /**
     * Inner tuple processing.  Returns an empty list but if all goes
     * well it should never be called.  It's only there for safety in
     * case things really go badly wrong and I've messed things up in
     * the rewrite.
     */
    @Override
    protected List<Tuple> innerProcessTuple(Tuple tuple, int inOp)
	throws EngineException {
        
        return new ArrayList<Tuple>();
    }  // innerProcessTuple()

    
    /**
     * Textual representation
     */
    protected String toStringSingle () {
        return "mj <" + getPredicate() + ">";
    } // toStringSingle()

} // MergeJoin
