
package com.datasphere.government.datalineage.gsp.dataflow.model;


public class DataFlowRelation extends AbstractRelation
{

	@Override
	public RelationType getRelationType( )
	{
		return RelationType.dataflow;
	}
}