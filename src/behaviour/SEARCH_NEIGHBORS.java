package behaviour;

import agent.DCOP;
import jade.core.behaviours.OneShotBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

public class SEARCH_NEIGHBORS extends OneShotBehaviour implements MESSAGE_TYPE {

	private static final long serialVersionUID = 6680449924898094747L;

	DCOP agent;
	
	public SEARCH_NEIGHBORS(DCOP agent) {
		super(agent);
		this.agent = agent;
	}
	
	@Override
	public void action() {
		DFAgentDescription templateDF = new DFAgentDescription();
		ServiceDescription serviceDescription = new ServiceDescription();
		serviceDescription.setType(agent.getIdStr());
		templateDF.addServices(serviceDescription);
		
		System.out.println("Agent " + agent.getIdStr() + " Start looking for neighbors: " + agent.getNeighborStrList());
				
		while (agent.getNeighborAIDList().size() < agent.getNeighborStrList().size()) {
			try {
				DFAgentDescription[] foundAgentList = DFService.search(myAgent, templateDF);
				agent.getNeighborAIDList().clear();
//				for (int foundAgentIndex=0; foundAgentIndex<foundAgentList.length; foundAgentIndex++) {
//					agent.getNeighborAIDList().add(foundAgentList[foundAgentIndex].getName());
//				}
				for (DFAgentDescription neighbor : foundAgentList) {
				  agent.getNeighborAIDList().add(neighbor.getName());
				}
			} catch (FIPAException e) {
				e.printStackTrace();
			}
//		  System.out.println("Agent " + agent.getIdStr() + " found: " + agent.getNeighborAIDList());
		}
		
		System.out.println("Agent " + agent.getIdStr() + " Done looking for neighbors." );
	}
}
