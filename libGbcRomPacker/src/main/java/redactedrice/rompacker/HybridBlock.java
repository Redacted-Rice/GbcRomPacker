package redactedrice.rompacker;

import java.io.IOException;

import redactedrice.gbcframework.QueuedWriter;
import redactedrice.gbcframework.addressing.AddressRange;
import redactedrice.gbcframework.addressing.AssignedAddresses;

public class HybridBlock 
{
	private FixedBlock fixed;
	private MovableBlock movable;
	
	public HybridBlock(MovableBlock block, int preferredStartAddress)
	{
		fixed = new FixedBlock(block.block, preferredStartAddress);
		movable = block;
	}
	
	public FixedBlock getFixedBlock() 
	{
		return fixed;
	}

	public MovableBlock getMovableBlock() 
	{
		return movable;
	}

	public void write(QueuedWriter writer, AssignedAddresses assignedAddresses) throws IOException
	{
		movable.write(writer, assignedAddresses);
	}
	
	public AddressRange createBlankedRangeForBlock(int size)
	{
		return fixed.createBlankedRangeForBlock(size);
	}
}
