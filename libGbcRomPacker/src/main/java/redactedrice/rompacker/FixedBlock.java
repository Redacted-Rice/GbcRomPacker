package redactedrice.rompacker;


import redactedrice.gbcframework.SegmentedByteBlock;
import redactedrice.gbcframework.addressing.AddressRange;
import redactedrice.gbcframework.addressing.BankAddress;

public class FixedBlock extends AllocBlock
{
	private BankAddress address;
	// The remote block (if needed), should be referred to in the DataBlock so no need to track it here
	
	public FixedBlock(SegmentedByteBlock code, int fixedStartAddress)
	{
		super(code);
		address = new BankAddress(fixedStartAddress);
	}

	public BankAddress getFixedAddress() 
	{
		return new BankAddress(address);
	}
	
	public AddressRange createBlankedRangeForBlock(int size)
	{
		return new AddressRange(address, size);
	}
}
