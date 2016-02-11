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

import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.io.IOException;

import org.dejave.attica.model.Relation;
import org.dejave.attica.engine.predicates.Predicate;
import org.dejave.attica.engine.predicates.PredicateEvaluator;
import org.dejave.attica.engine.predicates.PredicateTupleInserter;
import org.dejave.attica.storage.IntermediateTupleIdentifier;
import org.dejave.attica.storage.Page;
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
public class MergeJoin extends PhysicalJoin {
	
    /** The name of the temporary file for the output. */
    private String outputFile;
    
    /** The relation manager used for I/O. */
    private RelationIOManager outputMan;
    
    /** The pointer to the left sort attribute. */
    private int leftSlot;
	
    /** The pointer to the right sort attribute. */
    private int rightSlot;

    /** The iterator over the output file. */
    private Iterator<Tuple> outputTuples;

    /** Reusable output list. */
    private List<Tuple> returnList;
    
    /** The name of the temporary file for the right input. */
    private String rightFile;
    
    /**
     * Constructs a new mergejoin operator.
     * 
     * @param left the left input operator.
     * @param right the right input operator.
     * @param sm the storage manager.
     * @param leftSlot pointer to the left sort attribute.
     * @param rightSlot pointer to the right sort attribute.
     * @param predicate the predicate evaluated by this join operator.
     * @throws EngineException thrown whenever the operator cannot be
     * properly constructed.
     */
    public MergeJoin(Operator left, 
                     Operator right,
                     StorageManager sm,
                     int leftSlot,
                     int rightSlot,
                     Predicate predicate) 
	throws EngineException {
        
        super(left, right, sm, predicate);
        this.leftSlot = leftSlot;
        this.rightSlot = rightSlot;
        returnList = new ArrayList<Tuple>(); 
        try {
            // Initialise the right temporary file for right input
        	rightFile = FileUtil.createTempFileName();
        	sm.createFile(rightFile);
            initTempFiles();
        }
        catch (StorageManagerException sme) {
            EngineException ee = new EngineException("Could not instantiate " +
                                                     "merge join");
            ee.setStackTrace(sme.getStackTrace());
            throw ee;
        }
    } // MergeJoin()


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
        getStorageManager().createFile(outputFile);
    } // initTempFiles()

    //Compare method used to compare two specific slots of two tuples
    private int compare(Tuple t1, Tuple t2, int leftSlot, int rightSlot){

        return t1.getValue(leftSlot).compareTo(t2.getValue(rightSlot));
    }

    /**
     * Sets up this grace hash join operator.
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
            
            //Store the right input
            //N.B. There is no need to store left input
            //Because there is no backtracking on left input. Scan it once is enough
            Relation rightRel = getInputOperator(RIGHT).getOutputRelation();
            RelationIOManager rightMan = new RelationIOManager(getStorageManager(), rightRel, rightFile);
            boolean done = false;

            while (! done)
            {
                Tuple tuple = getInputOperator(RIGHT).getNext();
                if (tuple != null)
                {
                    done = (tuple instanceof EndOfStreamTuple);
                    if (! done)
                    {
                        rightMan.insertTuple(tuple);
                    }
                }
            }
            
            //Initialise two iterator for left and right input
            outputMan = new RelationIOManager(getStorageManager(), getOutputRelation(), outputFile);
            Iterator<Tuple> rightIt = rightMan.tuples().iterator();
            Iterator<Tuple> leftIt = getInputOperator(LEFT).tuples().iterator();
            Tuple leftTuple = null;
            Tuple rightTuple = null;
            if (leftIt.hasNext())
            {
                leftTuple = leftIt.next();
            }
            if (rightIt.hasNext())
            {
                rightTuple = rightIt.next();
            }
            done = false;
            
            //Merge join
            //When there are more tuples both in left and right input
            while (leftTuple != null && rightTuple != null && ! done)
            {
                //When left < right, advance left
                while (compare(leftTuple, rightTuple, leftSlot, rightSlot) < 0)
                {
                    if (leftIt.hasNext())
                    {
                        leftTuple = leftIt.next();
                    }
                    else
                    {
                        done = true;
                        break;
                    }
                }
                //When left > right, advance right
                while (compare(leftTuple, rightTuple, leftSlot, rightSlot) > 0)
                {
                    if (rightIt.hasNext())
                    {
                        rightTuple = rightIt.next();
                    }
                    else
                    {
                        done = true;
                        break;
                    }
                }
                //When left == right, combine the tuples
                if (compare(leftTuple, rightTuple, leftSlot, rightSlot) == 0)
                {
                    //Temporary file used to store the right tuples which are in group
                    String tempFile = FileUtil.createTempFileName();
                    getStorageManager().createFile(tempFile);
                    RelationIOManager tempMan = new RelationIOManager(getStorageManager(), rightRel, tempFile);
                    Tuple markTuple = rightTuple;
                    //Store the right in-group tuples
                    while (compare(leftTuple, rightTuple, leftSlot, rightSlot) == 0)
                    {
                        tempMan.insertTuple(rightTuple);
                        if (rightIt.hasNext())
                        {
                            rightTuple = rightIt.next();
                        }
                        else
                        {
                            done = true;
                            break;
                        }
                    }
                    //Begin to advance left to match the right in-group tuples
                    while (compare(leftTuple, markTuple, leftSlot, rightSlot) == 0)
                    {
                        for (Tuple tuple : tempMan.tuples())
                        {
                            Tuple newTuple = combineTuples(leftTuple, tuple);
                            outputMan.insertTuple(newTuple);
                        }
                        if (leftIt.hasNext())
                        {
                            leftTuple = leftIt.next();
                        }
                        else
                        {
                            done = true;
                            break;
                        }
                    }
                    //Delete the temporary file
                    getStorageManager().deleteFile(tempFile);
                }
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
            //                                 outputFile);

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
            
            getStorageManager().deleteFile(rightFile);
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
