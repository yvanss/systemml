/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.runtime.codegen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.instructions.cp.DoubleObject;
import org.apache.sysml.runtime.instructions.cp.ScalarObject;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.data.SparseBlock;

public abstract class SpoofOuterProduct extends SpoofOperator
{
	private static final long serialVersionUID = 2948612259863710279L;
	
	private static final int L2_CACHESIZE = 256 * 1024; //256KB (common size)
	
	public enum OutProdType {
		LEFT_OUTER_PRODUCT,
		RIGHT_OUTER_PRODUCT,
		CELLWISE_OUTER_PRODUCT, // (e.g., X*log(sigmoid(-(U%*%t(V)))))  )
		AGG_OUTER_PRODUCT		// (e.g.,sum(X*log(U%*%t(V)+eps)))   )
	}
	
	protected OutProdType _outerProductType;
	
	public SpoofOuterProduct() {

	}
	
	public void setOuterProdType(OutProdType type) {
		_outerProductType = type;
	}
	
	public OutProdType getOuterProdType() {
		return _outerProductType;
	}
	
	@Override
	public ScalarObject execute(ArrayList<MatrixBlock> inputs, ArrayList<ScalarObject> scalarObjects)	
		throws DMLRuntimeException
	{
		//sanity check
		if( inputs==null || inputs.size() < 3 )
			throw new RuntimeException("Invalid input arguments.");
		
		//input preparation
		double[][] b = prepInputMatrices(inputs, 3);
		double[] scalars = prepInputScalars(scalarObjects);
		
		//core sequential execute
		final int m = inputs.get(0).getNumRows();
		final int n = inputs.get(0).getNumColumns();	
		final int k = inputs.get(1).getNumColumns(); // rank
		
		//public static void matrixMultWDivMM(MatrixBlock mW, MatrixBlock mU, MatrixBlock mV, MatrixBlock mX, MatrixBlock ret, WDivMMType wt, int k)
		MatrixBlock a = inputs.get(0);
		MatrixBlock u = inputs.get(1);
		MatrixBlock v = inputs.get(2);
		
		MatrixBlock out = new MatrixBlock(1, 1, false);
		out.allocateDenseBlock();
		
		if(!a.isInSparseFormat())
			executeCellwiseDense(a.getDenseBlock(), u.getDenseBlock(), v.getDenseBlock(), b, scalars, out.getDenseBlock(), n, m, k, _outerProductType, 0, m, 0, n);
		else
			executeCellwiseSparse(a.getSparseBlock(), u.getDenseBlock(), v.getDenseBlock(), b, scalars, out, n, m, k, (int) a.getNonZeros(), _outerProductType, 0, m, 0, n);
		return new DoubleObject(out.getDenseBlock()[0]);
	}
	
	@Override
	public ScalarObject execute(ArrayList<MatrixBlock> inputs, ArrayList<ScalarObject> scalarObjects, int numThreads)	
		throws DMLRuntimeException
	{
		//sanity check
		if( inputs==null || inputs.size() < 3 )
			throw new RuntimeException("Invalid input arguments.");
		
		//input preparation
		double[][] b = prepInputMatrices(inputs, 3);
		double[] scalars = prepInputScalars(scalarObjects);
		
		//core sequential execute
		final int m = inputs.get(0).getNumRows();
		final int n = inputs.get(0).getNumColumns();	
		final int k = inputs.get(1).getNumColumns(); // rank
		double sum = 0;
		
		try 
		{			
			ExecutorService pool = Executors.newFixedThreadPool(k);
			ArrayList<ParOuterProdAggTask> tasks = new ArrayList<ParOuterProdAggTask>();			
			//create tasks (for wdivmm-left, parallelization over columns;
			//for wdivmm-right, parallelization over rows; both ensure disjoint results)
			int blklen = (int)(Math.ceil((double)m/numThreads));
			for( int i=0; i<numThreads & i*blklen<m; i++ )
				tasks.add(new ParOuterProdAggTask(inputs.get(0), inputs.get(1).getDenseBlock(), inputs.get(2).getDenseBlock(), b, scalars, n, m, k, _outerProductType, i*blklen, Math.min((i+1)*blklen,m), 0, n));
			//execute tasks
			List<Future<Double>> taskret = pool.invokeAll(tasks);
			pool.shutdown();
			for( Future<Double> task : taskret )
				sum += task.get();
		} 
		catch (Exception e) {
			throw new DMLRuntimeException(e);
		} 
		
		return new DoubleObject(sum);	
	}
	
	public void execute(ArrayList<MatrixBlock> inputs, ArrayList<ScalarObject> scalarObjects, MatrixBlock out)	
		throws DMLRuntimeException
	{
		//sanity check
		if( inputs==null || inputs.size() < 3 || out==null )
			throw new RuntimeException("Invalid input arguments.");
		
		//check empty result
		if(   (_outerProductType == OutProdType.LEFT_OUTER_PRODUCT && inputs.get(1).isEmptyBlock(false)) //U is empty
				   || (_outerProductType == OutProdType.RIGHT_OUTER_PRODUCT &&  inputs.get(2).isEmptyBlock(false)) //V is empty
				   || (_outerProductType == OutProdType.CELLWISE_OUTER_PRODUCT && inputs.get(0).isEmptyBlock(false))) {  //X is empty
					out.examSparsity(); //turn empty dense into sparse
					return; 
		}
		
		//input preparation and result allocation (Allocate the output that is set by Sigma2CPInstruction) 
		if(_outerProductType == OutProdType.CELLWISE_OUTER_PRODUCT) {
			//assign it to the time and sparse representation of the major input matrix
			out.reset(inputs.get(0).getNumRows(), inputs.get(0).getNumColumns(), inputs.get(0).isInSparseFormat());
			out.allocateDenseOrSparseBlock();
		}		
		else {	
			//if left outerproduct gives a value of k*n instead of n*k, change it back to n*k and then transpose the output
			//if(_outerProductType == OutProdType.LEFT_OUTER_PRODUCT &&  out.getNumRows() == inputs.get(2).getNumColumns() &&  out.getNumColumns() == inputs.get(2).getNumRows())
			if(_outerProductType == OutProdType.LEFT_OUTER_PRODUCT )
				out.reset(inputs.get(0).getNumColumns(),inputs.get(1).getNumColumns()); // n*k
			else if(_outerProductType == OutProdType.RIGHT_OUTER_PRODUCT )
				out.reset(inputs.get(0).getNumRows(),inputs.get(1).getNumColumns()); // m*k
			out.allocateDenseBlock();
		}			
		
		//input preparation
		double[][] b = prepInputMatrices(inputs, 3);
		double[] scalars = prepInputScalars(scalarObjects);
				
		//core sequential execute
		final int m = inputs.get(0).getNumRows();
		final int n = inputs.get(0).getNumColumns();	
		final int k = inputs.get(1).getNumColumns(); // rank
		
		MatrixBlock a = inputs.get(0);
		MatrixBlock u = inputs.get(1);
		MatrixBlock v = inputs.get(2);
		
		switch(_outerProductType) {
			case LEFT_OUTER_PRODUCT:	
			case RIGHT_OUTER_PRODUCT:
				if( !a.isInSparseFormat() )
					executeDense(a.getDenseBlock(), u.getDenseBlock(), v.getDenseBlock(), b, scalars, out.getDenseBlock(), n, m, k, _outerProductType, 0, m, 0, n);
				else
					executeSparse(a.getSparseBlock(), u.getDenseBlock(), v.getDenseBlock(), b, scalars, out.getDenseBlock(), n, m, k, (int) a.getNonZeros(), _outerProductType, 0, m, 0, n);
				break;
				
			case CELLWISE_OUTER_PRODUCT:
				if( !a.isInSparseFormat() )
					executeCellwiseDense(a.getDenseBlock(), u.getDenseBlock(), v.getDenseBlock(), b, scalars, out.getDenseBlock(), n, m, k, _outerProductType, 0, m, 0, n);
				else 
					executeCellwiseSparse(a.getSparseBlock(), u.getDenseBlock(), v.getDenseBlock(), b, scalars, out, n, m, k, (int) a.getNonZeros(), _outerProductType, 0, m, 0, n);
				break;
	
			case AGG_OUTER_PRODUCT:
				throw new DMLRuntimeException("Wrong codepath for aggregate outer product.");	
		}
		
		//post-processing
		out.recomputeNonZeros();
		out.examSparsity();
	}
	
	@Override
	public void execute(ArrayList<MatrixBlock> inputs, ArrayList<ScalarObject> scalarObjects, MatrixBlock out, int numThreads)	
		throws DMLRuntimeException
	{
		//sanity check
		if( inputs==null || inputs.size() < 3 || out==null )
			throw new RuntimeException("Invalid input arguments.");
		
		//check empty result
		if(   (_outerProductType == OutProdType.LEFT_OUTER_PRODUCT && inputs.get(1).isEmptyBlock(false)) //U is empty
				   || (_outerProductType == OutProdType.RIGHT_OUTER_PRODUCT &&  inputs.get(2).isEmptyBlock(false)) //V is empty
				   || (_outerProductType == OutProdType.CELLWISE_OUTER_PRODUCT && inputs.get(0).isEmptyBlock(false))) {  //X is empty
					out.examSparsity(); //turn empty dense into sparse
					return; 
		}
		
		//input preparation and result allocation (Allocate the output that is set by Sigma2CPInstruction) 
		if(_outerProductType == OutProdType.CELLWISE_OUTER_PRODUCT)
		{
			//assign it to the time and sparse representation of the major input matrix
			out.reset(inputs.get(0).getNumRows(), inputs.get(0).getNumColumns(), inputs.get(0).isInSparseFormat());
			out.allocateDenseOrSparseBlock();
		}
		else
		{
			//if left outerproduct gives a value of k*n instead of n*k, change it back to n*k and then transpose the output
			//if(_outerProductType == OutProdType.LEFT_OUTER_PRODUCT &&  out.getNumRows() == inputs.get(2).getNumColumns() &&  out.getNumColumns() == inputs.get(2).getNumRows())
			if(_outerProductType == OutProdType.LEFT_OUTER_PRODUCT )
				out.reset(inputs.get(0).getNumColumns(),inputs.get(1).getNumColumns()); // n*k
			else if(_outerProductType == OutProdType.RIGHT_OUTER_PRODUCT )
				out.reset(inputs.get(0).getNumRows(),inputs.get(1).getNumColumns()); // m*k
			out.allocateDenseBlock();
		}	
		
		//input preparation
		double[][] b = prepInputMatrices(inputs, 3);
		double[] scalars = prepInputScalars(scalarObjects);
		
		//core sequential execute
		final int m = inputs.get(0).getNumRows();
		final int n = inputs.get(0).getNumColumns();	
		final int k = inputs.get(1).getNumColumns(); // rank
		
		try 
		{			
			ExecutorService pool = Executors.newFixedThreadPool(numThreads);
			ArrayList<ParExecTask> tasks = new ArrayList<ParExecTask>();			
			//create tasks (for wdivmm-left, parallelization over columns;
			//for wdivmm-right, parallelization over rows; both ensure disjoint results)
			
			if( _outerProductType == OutProdType.LEFT_OUTER_PRODUCT ) {
				int blklen = (int)(Math.ceil((double)n/numThreads));
				for( int j=0; j<numThreads & j*blklen<n; j++ )
					tasks.add(new ParExecTask(inputs.get(0), inputs.get(1).getDenseBlock(), inputs.get(2).getDenseBlock(), b, scalars, out, n, m, k, _outerProductType,  0, m, j*blklen, Math.min((j+1)*blklen, n)));
			}
			else { ///right // cellwise
				int blklen = (int)(Math.ceil((double)m/numThreads));
				for( int i=0; i<numThreads & i*blklen<m; i++ )
					tasks.add(new ParExecTask(inputs.get(0), inputs.get(1).getDenseBlock(), inputs.get(2).getDenseBlock(), b, scalars, out, n, m, k, _outerProductType, i*blklen, Math.min((i+1)*blklen,m), 0, n));
			}
			List<Future<Long>> taskret = pool.invokeAll(tasks);
			pool.shutdown();
			for( Future<Long> task : taskret )
				out.setNonZeros(out.getNonZeros() + task.get());
		} 
		catch (Exception e) {
			throw new DMLRuntimeException(e);
		} 
		
		//post-processing
		out.examSparsity();
	}
	
	private void executeDense(double[] a, double[] u, double[] v, double[][] b, double[] scalars , double[] c, int n, int m, int k, OutProdType type, int rl, int ru, int cl, int cu ) 
	{
		//approach: iterate over non-zeros of w, selective mm computation
		//cache-conscious blocking: due to blocksize constraint (default 1000),
		//a blocksize of 16 allows to fit blocks of UV into L2 cache (256KB) 
		
		final int blocksizeIJ = 16; //u/v block (max at typical L2 size) 
		int cix = 0;
		//blocked execution
		for( int bi = rl; bi < ru; bi+=blocksizeIJ )
			for( int bj = cl, bimin = Math.min(ru, bi+blocksizeIJ); bj < cu; bj+=blocksizeIJ ) 
			{
				int bjmin = Math.min(cu, bj+blocksizeIJ);
						
				//core computation
				for( int i=bi, ix=bi*n, uix=bi*k; i<bimin; i++, ix+=n, uix+=k )
					for( int j=bj, vix=bj*k; j<bjmin; j++, vix+=k)
						if( a[ix+j] != 0 ) {
							cix = (type == OutProdType.LEFT_OUTER_PRODUCT) ? vix : uix;
							genexecDense( a[ix+j], u, uix, v, vix, b, scalars, c, cix, n, m, k, i,j);//(ix+j)/n, (ix+j)%n ); 
						}
			}
	}
	
	private void executeCellwiseDense(double[] a, double[] u, double[] v, double[][] b, double[] scalars , double[] c, int n, int m, int k, OutProdType type, int rl, int ru, int cl, int cu ) 
	{
		//approach: iterate over non-zeros of w, selective mm computation
		//cache-conscious blocking: due to blocksize constraint (default 1000),
		//a blocksize of 16 allows to fit blocks of UV into L2 cache (256KB) 
		
		final int blocksizeIJ = 16; //u/v block (max at typical L2 size) 
		//blocked execution
		for( int bi = rl; bi < ru; bi+=blocksizeIJ )
			for( int bj = cl, bimin = Math.min(ru, bi+blocksizeIJ); bj < cu; bj+=blocksizeIJ ) 
			{
				int bjmin = Math.min(cu, bj+blocksizeIJ);
						
				//core computation
				for( int i=bi, ix=bi*n, uix=bi*k; i<bimin; i++, ix+=n, uix+=k )
					for( int j=bj, vix=bj*k; j<bjmin; j++, vix+=k)
						if( a[ix+j] != 0 ) {
							//int cix = (type == OutProdType.LEFT_OUTER_PRODUCT) ? vix : uix;
							if(type == OutProdType.CELLWISE_OUTER_PRODUCT)
								c[ix+j] = genexecCellwise( a[ix+j], u, uix, v, vix, b, scalars, n, m, k, i, j ); 
							else
								c[0]  += genexecCellwise( a[ix+j], u, uix, v, vix, b, scalars, n, m, k, i, j); // (ix+j)/n, (ix+j)%n ); 	
						}
			}
	}
	
	private void executeSparse(SparseBlock sblock,  double[] u, double[] v, double[][] b, double[] scalars , double[] c, int n, int m, int k, int nnz, OutProdType type, int rl, int ru, int cl, int cu) 
	{
		boolean left = (_outerProductType== OutProdType.LEFT_OUTER_PRODUCT);
		
		//approach: iterate over non-zeros of w, selective mm computation
		//blocked over ij, while maintaining front of column indexes, where the
		//blocksize is chosen such that we reuse each  Ui/Vj vector on average 8 times,
		//with custom blocksizeJ for wdivmm_left to avoid LLC misses on output.
		final int blocksizeI = (int) (8L*m*n/nnz);
		final int blocksizeJ = left ? Math.max(8,Math.min(L2_CACHESIZE/(k*8), blocksizeI)) : blocksizeI;
		int[] curk = new int[blocksizeI];
		
		for( int bi = rl; bi < ru; bi+=blocksizeI ) 
		{
			int bimin = Math.min(ru, bi+blocksizeI);
			//prepare starting indexes for block row
			for( int i=bi; i<bimin; i++ ) {
				int index = (cl==0||sblock.isEmpty(i)) ? 0 : sblock.posFIndexGTE(i,cl);
				curk[i-bi] = (index>=0) ? index : n;
			}
			
			//blocked execution over column blocks
			for( int bj = cl; bj < cu; bj+=blocksizeJ ) 
			{
				int bjmin = Math.min(cu, bj+blocksizeJ);
				//core wdivmm block matrix mult
				for( int i=bi, uix=bi*k; i<bimin; i++, uix+=k ) {
					if( sblock.isEmpty(i) ) continue;
					
					int wpos = sblock.pos(i);
					int wlen = sblock.size(i);
					int[] wix = sblock.indexes(i);
					double[] wval = sblock.values(i);				

					int index = wpos + curk[i-bi];
					for( ; index<wpos+wlen && wix[index]<bjmin; index++ ) {
						genexecDense( wval[index], u, uix, v, wix[index]*k, b, scalars, c, 
								(left ? wix[index]*k : uix), n, m, k, i, wix[index] );
					}
					curk[i-bi] = index - wpos;
				}
			}
		}
	}
	
	private void executeCellwiseSparse(SparseBlock sblock, double[] u, double[] v, double[][] b, double[] scalars , MatrixBlock out, int n, int m, int k, long nnz, OutProdType type, int rl, int ru, int cl, int cu ) 
	{
		final int blocksizeIJ = (int) (8L*m*n/nnz); 
		int[] curk = new int[blocksizeIJ];			
		
		if( !out.isInSparseFormat() ) //DENSE
		{
			double[] c = out.getDenseBlock();
			for( int bi=rl; bi<ru; bi+=blocksizeIJ ) {
				int bimin = Math.min(ru, bi+blocksizeIJ);
				//prepare starting indexes for block row
				Arrays.fill(curk, 0); 
				//blocked execution over column blocks
				for( int bj=0; bj<n; bj+=blocksizeIJ ) {
					int bjmin = Math.min(n, bj+blocksizeIJ);
					for( int i=bi, uix=bi*k; i<bimin; i++, uix+=k ) {
						if( sblock.isEmpty(i) ) continue;
						int wpos = sblock.pos(i);
						int wlen = sblock.size(i);
						int[] wix = sblock.indexes(i);
						double[] wval = sblock.values(i);
						int index = wpos + curk[i-bi];
						for( ; index<wpos+wlen && wix[index]<bjmin; index++ ) {
							if(type == OutProdType.CELLWISE_OUTER_PRODUCT)
								c[index] = genexecCellwise( wval[index], u, uix, v, wix[index]*k, b, scalars, n, m, k, i, wix[index] ); 
							else
								c[0] += genexecCellwise( wval[index], u, uix, v, wix[index]*k, b, scalars, n, m, k, i, wix[index]); // (ix+j)/n, (ix+j)%n );
						}
						curk[i-bi] = index - wpos;
					}
				}
			}
		}
		else //SPARSE
		{
			SparseBlock c = out.getSparseBlock();
			for( int bi=rl; bi<ru; bi+=blocksizeIJ ) {
				int bimin = Math.min(ru, bi+blocksizeIJ);
				//prepare starting indexes for block row
				Arrays.fill(curk, 0); 
				//blocked execution over column blocks
				for( int bj=0; bj<n; bj+=blocksizeIJ ) {
					int bjmin = Math.min(n, bj+blocksizeIJ);
					for( int i=bi, uix=bi*k; i<bimin; i++, uix+=k ) {
						if( sblock.isEmpty(i) ) continue;
						int wpos = sblock.pos(i);
						int wlen = sblock.size(i);
						int[] wix = sblock.indexes(i);
						double[] wval = sblock.values(i);
						int index = wpos + curk[i-bi];
						for( ; index<wpos+wlen && wix[index]<bjmin; index++ ) {
							c.append(i, index, genexecCellwise( wval[index], u, uix, v, 
									wix[index]*k, b, scalars, n, m, k, i, wix[index] )); 
						}
						curk[i-bi] = index - wpos;
					}
				}
			}
		}
	}

	protected abstract void genexecDense( double a, double[] u, int ui, double[] v, int vi, double[][] b, double[] scalars , double[] c, int ci, int n, int m, int k, int rowIndex, int colIndex );
	
	protected abstract double genexecCellwise( double a, double[] u, int ui, double[] v, int vi, double[][] b, double[] scalars , int n, int m, int k, int rowIndex, int colIndex);

	private class ParExecTask implements Callable<Long> 
	{
		private final MatrixBlock _a;
		private final double[] _u;
		private final double[] _v;
		private final double[][] _b;
		private final double[] _scalars;
		private final MatrixBlock _c;
		private final int _clen;
		private final int _rlen;
		private final int _k;
		private final OutProdType _type;
		private final int _rl;
		private final int _ru;
		private final int _cl;
		private final int _cu;
		
		protected ParExecTask( MatrixBlock a, double[] u, double[] v, double[][] b, double[] scalars , MatrixBlock c, int clen, int rlen, int k, OutProdType type, int rl, int ru, int cl, int cu ) {
			_a = a;
			_u = u;
			_v = v;
			_b = b;
			_c = c;
			_scalars = scalars;
			_clen = clen;
			_rlen = rlen;
			_k = k;
			_type = type;
			_rl = rl;
			_ru = ru;
			_cl = cl;
			_cu = cu;
		}
		
		@Override
		public Long call() throws DMLRuntimeException {
			switch(_type)
			{
				case LEFT_OUTER_PRODUCT:	
				case RIGHT_OUTER_PRODUCT:
					if( !_a.isInSparseFormat() )
						executeDense(_a.getDenseBlock(), _u, _v, _b, _scalars, _c.getDenseBlock(), _clen, _rlen, _k, _type, _rl, _ru, _cl, _cu);
					else
						executeSparse(_a.getSparseBlock(), _u, _v, _b, _scalars, _c.getDenseBlock(), _clen, _rlen, _k, (int) _a.getNonZeros(), _type,  _rl, _ru, _cl, _cu);
					break;
				case CELLWISE_OUTER_PRODUCT:
					if( !_c.isInSparseFormat() )
						executeCellwiseDense(_a.getDenseBlock(), _u, _v, _b, _scalars, _c.getDenseBlock(), _clen, _rlen, _k, _type, _rl, _ru, _cl, _cu);
					else 
						executeCellwiseSparse(_a.getSparseBlock(), _u, _v, _b, _scalars, _c, _clen, _rlen, _k, (int) _a.getNonZeros(), _type,  _rl, _ru, _cl, _cu);
					break;			
				case AGG_OUTER_PRODUCT:
					throw new DMLRuntimeException("Wrong codepath for aggregate outer product.");
			}
			
			int rl = _outerProductType == OutProdType.LEFT_OUTER_PRODUCT ? _cl : _rl;
			int ru = _outerProductType == OutProdType.LEFT_OUTER_PRODUCT ? _cu : _ru;
			return _c.recomputeNonZeros(rl, ru-1, 0, _c.getNumColumns()-1);
		}
	}
	
	private class ParOuterProdAggTask implements Callable<Double> 
	{
		private final MatrixBlock _a;
		private final double[] _u;
		private final double[] _v;
		private final double[][] _b;
		private final double[] _scalars;
		private final int _clen;
		private final int _rlen;
		private final int _k;
		private final OutProdType _type;
		private final int _rl;
		private final int _ru;
		private final int _cl;
		private final int _cu;
		
		protected ParOuterProdAggTask( MatrixBlock a, double[] u, double[] v, double[][] b, double[] scalars, int clen, int rlen, int k, OutProdType type, int rl, int ru, int cl, int cu ) {
			_a = a;
			_u = u;
			_v = v;
			_b = b;
			_scalars = scalars;
			_clen = clen;
			_rlen = rlen;
			_k = k;
			_type = type;
			_rl = rl;
			_ru = ru;
			_cl = cl;
			_cu = cu;
		}
		
		@Override
		public Double call() throws DMLRuntimeException {
			MatrixBlock out = new MatrixBlock(1, 1, false);
			out.allocateDenseBlock();
			if(!_a.isInSparseFormat())
				executeCellwiseDense(_a.getDenseBlock(), _u, _v, _b, _scalars, out.getDenseBlock(), _clen, _rlen, _k, _type, _rl, _ru, _cl, _cu);
			else
				executeCellwiseSparse(_a.getSparseBlock(), _u, _v, _b, _scalars, out, _clen, _rlen, _k, _a.getNonZeros(), _type, _rl, _ru, _cl, _cu);
			return out.getDenseBlock()[0];
		}
	}
}
