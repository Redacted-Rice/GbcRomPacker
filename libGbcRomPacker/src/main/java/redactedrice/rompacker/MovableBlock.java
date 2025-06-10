package redactedrice.rompacker;


import redactedrice.gbcframework.SegmentedByteBlock;
import redactedrice.gbcframework.addressing.BankRange;
import redactedrice.gbcframework.addressing.PrioritizedBankRange;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

public class MovableBlock extends AllocBlock
{
	public static final Comparator<MovableBlock> PRIORITY_SORTER = new PrioritySorter();
	
	private SortedSet<PrioritizedBankRange> allowableBankPreferences;
	private SortedSet<PrioritizedBankRange> unattemptedAllowableBankPreferences;

	protected MovableBlock(SegmentedByteBlock code)
	{
		this(code, new ArrayList<>());
	}
	
	public MovableBlock(SegmentedByteBlock code, int priority, byte startBank, byte stopBankExclusive)
	{
		this(code);
		addAllowableBankRange(priority, startBank, stopBankExclusive);
	}
	
	public MovableBlock(SegmentedByteBlock code, PrioritizedBankRange pref)
	{
		this(code, prefAsList(pref));
	}
	
	public MovableBlock(SegmentedByteBlock code, List<PrioritizedBankRange> prefs)
	{
		super(code);
		setMovableBlockCommonData(prefs);
	}
	
	private static ArrayList<PrioritizedBankRange> prefAsList(PrioritizedBankRange pref)
	{
		ArrayList<PrioritizedBankRange> prefAsList = new ArrayList<>();
		prefAsList.add(pref);
		return prefAsList;
	}
	
	private void setMovableBlockCommonData(List<PrioritizedBankRange> prefs)
	{		
		allowableBankPreferences = new TreeSet<>(PrioritizedBankRange.BASIC_SORTER);
		for (PrioritizedBankRange pref : prefs)
		{
			addAllowableBankRange(pref);
		}
	}

	public void addAllowableBankRange(PrioritizedBankRange bankPref)
	{
		allowableBankPreferences.add(new PrioritizedBankRange(bankPref));
	}
	
	public void addAllowableBankRange(int priority, BankRange bankRange)
	{
		allowableBankPreferences.add(new PrioritizedBankRange(priority, bankRange));
	}
	
	public void addAllowableBankRange(int priority, byte startBank, byte stopBankExclusive)
	{
		allowableBankPreferences.add(new PrioritizedBankRange(priority, startBank, stopBankExclusive));
	}
	
	public SortedSet<PrioritizedBankRange> getAllowableBankPreferences()
	{
		SortedSet<PrioritizedBankRange> copy = new TreeSet<>(PrioritizedBankRange.BASIC_SORTER);
		for (PrioritizedBankRange pref : allowableBankPreferences)
		{
			copy.add(new PrioritizedBankRange(pref));
		}
		return copy;
	}
	
	// Package
	void resetBankPreferences()
	{
		unattemptedAllowableBankPreferences = getAllowableBankPreferences();
	}
	
	// Package
	boolean isUnattemptedAllowableBanksEmpty()
	{
		return unattemptedAllowableBankPreferences.isEmpty();
	}
	
	// Package
	byte popNextUnattemptedAllowableBank()
	{
		if (isUnattemptedAllowableBanksEmpty())
		{
			return -1;
		}
		
		// get the next preference
		PrioritizedBankRange pref = new PrioritizedBankRange(unattemptedAllowableBankPreferences.first());
		byte prefId = pref.getStart();
		
		// Remove it from unattempted and update the preference
		removeUnattemptedBank(prefId);
		if (pref.isEmpty())
		{
			unattemptedAllowableBankPreferences.remove(pref);
		}
		
		// Return the id
		return prefId;
	}
	
	// Package
	int getNextUnatemptedAllowableBankPriority()
	{
		if (unattemptedAllowableBankPreferences.isEmpty())
		{
			return Byte.MAX_VALUE;
		}
		return unattemptedAllowableBankPreferences.first().getPriority();
	}
	
	private void removeUnattemptedBank(byte bank)
	{
		List<PrioritizedBankRange> modified = new LinkedList<>();
		Iterator<PrioritizedBankRange> iter = unattemptedAllowableBankPreferences.iterator();
		PrioritizedBankRange currPref;
		while (iter.hasNext())
		{
			currPref = iter.next();
			if (currPref.contains(bank))
			{
				// We always start with the first one in the range so that makes things easier since we don't have to worry about splitting banks
				iter.remove();
				currPref.shrink((byte) 1);
				if (!currPref.isEmpty())
				{
					modified.add(currPref);
				}
			}
		}
		
		for (PrioritizedBankRange pref : modified)
		{
			unattemptedAllowableBankPreferences.add(pref);
		}
	}
	
	public static class PrioritySorter implements Comparator<MovableBlock>
	{
		public int compare(MovableBlock a1, MovableBlock a2)
	    {   
			int compareVal = Integer.compare(a1.getNextUnatemptedAllowableBankPriority(), a2.getNextUnatemptedAllowableBankPriority());
			
			if (compareVal == 0)
			{
				// Give larger blocks higher priority - We have to do it agnostic to where things are
				// allocated but that is okay as this does not need to be 100% accurate
				compareVal = Integer.compare(a2.getWorstCaseSize(), a1.getWorstCaseSize());
			}
			
			if (compareVal == 0)
			{
				// if all else fails, use the unique block ID so things like sets will recognize these are not the same object
				compareVal = a1.getId().compareTo(a2.getId());
			}
			
			return compareVal;
	    }
	}
}
