/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2015
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */


package com.ibm.bi.dml.runtime.matrix.data;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.io.WritableComparable;

@SuppressWarnings("rawtypes")
public class Tagged<T extends WritableComparable> implements WritableComparable<Tagged>
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
		
//	private static final Log LOG = LogFactory.getLog(Tagged.class);
	protected byte tag=-1;
	protected T base;
	public static int TAG_SIZE=Integer.SIZE/8;
	public Tagged(T b, byte t)
	{		
		base=b;
		tag=t;
	}
	
	public Tagged()
	{
		
	}
	
	public byte getTag()
	{
		return tag;
	}
	public T getBaseObject()
	{
		return base;
	}
	public void setTag(byte t)
	{
		tag=t;
	}
	public void setBaseObject(T b)
	{
		base=b;
	}
	public void readFields(DataInput in) throws IOException {
		tag=in.readByte();
		base.readFields(in);
	}

	
	public void write(DataOutput out) throws IOException {
		out.writeByte(tag);
		base.write(out);
	}
	
	public String toString()
	{
		return base.toString()+" ~~ tag: "+tag;
	}
	
	 /** A Comparator optimized for Tagged. */ 
	public static class Comparator implements RawComparator<Tagged> {
		
		public int compare(byte[] b1, int s1, int l1,
	                       byte[] b2, int s2, int l2) {
	      byte thisValue = b1[s1];
	      byte thatValue = b2[s2];
	      return (thisValue-thatValue);
	    }

		@Override
		@SuppressWarnings("unchecked")
		public int compare(Tagged a, Tagged b) {
			if(a.tag!=b.tag)
				return a.tag-b.tag;
			else 
				return a.getBaseObject().compareTo(b.getBaseObject());
		}
	  }

	@Override
	@SuppressWarnings("unchecked")
	public int compareTo(Tagged other) {
		if(tag!=other.tag)
			return tag-other.tag;
		else 
			return getBaseObject().compareTo(other.getBaseObject());
	}
	
	@Override
	public boolean equals(Object o) {
		if( !(o instanceof Tagged) )
			return false;
		Tagged that = (Tagged)o;
		return (tag==that.tag && getBaseObject().equals(that.getBaseObject()));
	}
	
	@Override
	public int hashCode() {
		throw new RuntimeException("hashCode() should never be called on instances of this class.");
	}
}
