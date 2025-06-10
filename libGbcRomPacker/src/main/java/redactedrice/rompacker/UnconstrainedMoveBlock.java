package redactedrice.rompacker;


import java.util.List;
import java.util.SortedSet;

import redactedrice.gbcframework.SegmentedByteBlock;
import redactedrice.gbcframework.RomConstants;
import redactedrice.gbcframework.addressing.PrioritizedBankRange;

public class UnconstrainedMoveBlock extends MovableBlock
{
	public UnconstrainedMoveBlock(SegmentedByteBlock code)
	{
		super(code);
	}
	
	public UnconstrainedMoveBlock(SegmentedByteBlock code, int priority, byte startBank, byte stopBank)
	{
		super(code, priority, startBank, stopBank);
	}
	
	public UnconstrainedMoveBlock(SegmentedByteBlock code, PrioritizedBankRange pref)
	{
		super(code, pref);
	}
	
	public UnconstrainedMoveBlock(SegmentedByteBlock code, List<PrioritizedBankRange> prefs)
	{
		super(code, prefs);
	}

	@Override
	public SortedSet<PrioritizedBankRange> getAllowableBankPreferences()
	{
		SortedSet<PrioritizedBankRange> toReturn = super.getAllowableBankPreferences();
		toReturn.add(new PrioritizedBankRange(Integer.MAX_VALUE, (byte) 0, (byte) (RomConstants.NUMBER_OF_BANKS - 1)));
		return toReturn;
	}
}
