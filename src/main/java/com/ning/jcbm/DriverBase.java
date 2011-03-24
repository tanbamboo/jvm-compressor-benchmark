package com.ning.jcbm;

import java.io.*;

import com.sun.japex.Constants;
import com.sun.japex.JapexDriverBase;
import com.sun.japex.TestCase;

/**
 */
public abstract class DriverBase extends JapexDriverBase
{
    protected final String _driverName;
    
    /**
     * Operation we are testing
     */
    protected Operation _operation;

    /**
     * Whether driver should use streaming compression or not (block-based)
     */
    protected boolean _streaming;
    
    /**
     * Number of bytes processed during test
     */
    protected int _totalLength;

    /**
     * Directory in which input files are stored (file names
     * are same as test case names)
     */
    private File _inputDir;

    private File _inputFile;
    
    /**
     * Uncompressed test data
     */
    protected byte[] _uncompressed;

    /**
     * Compressed test data
     */
    protected byte[] _compressed;

    protected DriverBase(String name)
    {
        _driverName = name;
    }
    
    @Override
    public void initializeDriver()
    {
        // Where are the input files?
        String dirName = getParam("japex.inputDir");
        if (dirName == null) {
            throw new RuntimeException("japex.inputFile not specified");
        }
        _inputDir = new File(dirName);
        if (!_inputDir.exists() || !_inputDir.isDirectory()) {
            throw new IllegalArgumentException("No input directory '"+_inputDir.getAbsolutePath()+"'");
        }
        _streaming = this.getBooleanParam("japex.streaming");
    }
    
    @Override
    public void prepare(TestCase testCase)
    {
        String name = testCase.getName();

        
        String[] parts = name.split(":");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid test name '"+name+"'; should have at least 2 components separated by slash");
        }
        _operation = null;
        String operStr = parts[0];
        String filename = parts[1];
        if ("C".equals(operStr)) {
            _operation = Operation.COMPRESS;
        } else if ("U".equals(operStr)) {
            _operation = Operation.UNCOMPRESS;            
        } else {
            throw new IllegalArgumentException("Invalid 'operation' part of name, '"+operStr+"': should be 'C' or 'U'");
        }
        _inputFile = new File(_inputDir, filename); 
        try {
            // First things first: load uncompressed input in memory; compress to verify round-tripping
            _uncompressed = loadFile(_inputFile);
            _compressed = compressBlock(_uncompressed);
            verifyRoundTrip(testCase.getName(), _uncompressed, _compressed);
        }
        catch (RuntimeException e) {
            throw e;
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected void verifyRoundTrip(String name, byte[] raw, byte[] compressed) throws IOException
    {
        byte[] actual = uncompressBlock(compressed);
        if (actual.length != raw.length) {
            throw new IllegalArgumentException("Round-trip failed for driver '"+_driverName+"', input '"+name+"': uncompressed length was "+actual.length+" bytes; expected "+raw.length);
        }
        int i = 0;
        for (int len = actual.length; i < len; ++i) {
            if (actual[i] != raw[i]) {
                throw new IllegalArgumentException("Round-trip failed for driver '"+_driverName+"', input '"+name+"': bytes at offset "+i
                        +" (from total of "+len+") differed, expected 0x"+Integer.toHexString(raw[i] & 0xFF)
                        +", got 0x"+Integer.toHexString(actual[i] & 0xFF));
            }
        }
    }
    
    @Override
    public void warmup(TestCase testCase) {
        run(testCase);
    }

    @Override
    public void run(TestCase testCase)
    {
        _totalLength = 0;

        try {
            if (_operation == Operation.COMPRESS) {
                if (_streaming) {
                } else {
                    byte[] stuff = compressBlock(_uncompressed);
                    _totalLength = stuff.length;
                }
            } else { // uncompress
                if (_streaming) {
                } else {
                    byte[] stuff = uncompressBlock(_compressed);
                    _totalLength = stuff.length;
                }
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        }
    }   

    @Override
    public void finish(TestCase testCase)
    {
        // Set compressed size in KB on X axis

        testCase.setDoubleParam("japex.resultValueX", ((double) _compressed.length) / 1024.0);
        getTestSuite().setParam("japex.resultUnitX", "KB");

        testCase.setParam("japex.inputFile", _inputFile.getAbsolutePath());

        // And then processing speed relative to input, in Mbps
        
        // Throughput choices; Mbps, tps; let's use Mbps to get relative speed (tps has issues with widely varying file sizes)
//        testCase.setDoubleParam("japex.resultValue", (double) _uncompressed.length / (1024.0 * 1024.0));
//        getTestSuite().setParam("japex.resultUnit", "tps"); // or mbps
        
        double itersPerSec = 1000.0 * testCase.getDoubleParam(Constants.RUN_ITERATIONS_SUM) / testCase.getDoubleParam(Constants.ACTUAL_RUN_TIME);
        
        testCase.setDoubleParam("japex.resultValue", itersPerSec * _uncompressed.length / (1024.0 * 1024.0));
        testCase.setParam("japex.resultUnit", "MB/s");
    }

    /*
    ///////////////////////////////////////////////////////////////////////
    // Abstract methods for sub-classes to implement
    ///////////////////////////////////////////////////////////////////////
     */

    protected abstract byte[] compressBlock(byte[] uncompressed) throws IOException;

    protected abstract byte[] uncompressBlock(byte[] compressed) throws IOException;
    
    /*
    ///////////////////////////////////////////////////////////////////////
    // Internal methods
    ///////////////////////////////////////////////////////////////////////
     */
    
    protected byte[] loadFile(File file) throws IOException
    {
        FileInputStream in = new FileInputStream(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
        byte[] buffer = new byte[4000];
        int count;
        
        while ((count = in.read(buffer)) > 0) {
            out.write(buffer, 0, count);
        }
        in.close();
        out.close();
        return out.toByteArray();
    }
}
