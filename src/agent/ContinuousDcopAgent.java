package agent; //dcop

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import behavior.AGENT_TERMINATE;
import behavior.BROADCAST_RECEIVE_HEURISTIC_INFO;
import behavior.DPOP_UTIL;
import behavior.DPOP_VALUE;
import behavior.DISCRETE_DSA;
import behavior.CONTINUOUS_DSA;
import behavior.PSEUDOTREE_GENERATION;
import behavior.RECEIVE_SEND_UTIL_TO_ROOT;
import behavior.SEARCH_NEIGHBORS;
import behavior.MAXSUM_FUNCTION_TO_VARIABLE;
import behavior.MAXSUM_VARIABLE_TO_FUNCTION;
import function.Interval;
import function.multivariate.MultivariateQuadFunction;
import function.multivariate.PiecewiseMultivariateQuadFunction;
import table.Row;
import table.Table;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.SequentialBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import maxsum.MaxSumMessage;

import static java.lang.System.*;
import static java.lang.Double.*;

/**
 * @author khoihd
 *
 */
public class ContinuousDcopAgent extends Agent implements DcopConstants {

	private static final long serialVersionUID = 2919994686894853596L;

	public static final double GRADIENT_SCALING_FACTOR = Math.pow(10, -3);

	public static final double DSA_PROBABILITY = 0.6;

	private static String inputFileName;
	private static Interval globalInterval;
	// for writing output purposes
	private static int instanceID;
	private static int noAgent;
	private static int algorithm;
	private static int gradientIteration;
	private static int numberOfPoints;

	private Map<String, Double> neighborValueMap = new HashMap<>();

	private String agentID;
	private boolean isRoot;
	private boolean isLeaf;

	private AID parentAID;
	private List<AID> childrenAIDList = new ArrayList<>();
	private Set<AID> neighborAIDSet = new HashSet<>();
	private List<AID> pseudoParentAIDList = new ArrayList<>();
	private List<AID> pseudoChildrenAIDList = new ArrayList<>();
	private List<String> parentAndPseudoStrList = new ArrayList<>();
	private Set<String> neighborStrSet = new HashSet<>();

	private List<Table> tableList = new ArrayList<>();
	private List<Table> constraintTableWithoutRandomList = new ArrayList<>();
	private List<Table> organizedConstraintTableList = new ArrayList<>();

	private Map<String, List<String>> decisionVariableDomainMap = new HashMap<>();
	// map TS -> constraint table list (if local_search)
	// map TS -> 1 collapsed table list (if collapsed dpop)
	private Map<Integer, List<Table>> constraintTableAtEachTSMap = new HashMap<>();
	private Table collapsedSwitchingCostTable;

	// VALUE phase
	private Map<String, Double> valuesToSendInVALUEPhase = new HashMap<>();

	// used for LOCAL SEARCH
	private Map<Integer, Double> valueAtEachTSMap = new HashMap<>();
	// List<Double> utilityAtEachTSList;
	// agent -> <values0, values1, ..., values_n>
	private List<Double> currentGlobalUtilityList = new ArrayList<>();
	private List<String> bestImproveValueList = new ArrayList<>();
	private double currentGlobalUtility;
	private double totalGlobalUtility;
	private double utilFromChildren;

	private Table agentViewTable;
	private Double value;
	private Map<Integer, String> pickedRandomMap = new HashMap<>();

	private int currentTS;

	private long startTime;
	private long actualEndTime;
	private int lsIteration;

	// simulated time
	private ThreadMXBean bean;
	private long simulatedTime = 0;
	private long currentStartTime;
	/**
	 * Time needed to send a message
	 */
	private static long delayMessageTime = 0;

	// for reuse information
	private Map<AID, Integer> constraintInfoMap = new HashMap<>();
	/** Used in building pseudo-tree */
	private boolean notVisited = true;

	private double aggregatedUtility;
//	private String lastLine;
	private int rootFromInput = Integer.MAX_VALUE;

	// mapping <neighbor, function<>
	private Map<String, PiecewiseMultivariateQuadFunction> functionMap = new HashMap<>();
	private PiecewiseMultivariateQuadFunction agentViewFunction;
	private Map<String, PiecewiseMultivariateQuadFunction> pwFunctionWithPParentMap = new HashMap<>();

	private int numberOfApproxAgents;
	private boolean isApprox;
	private int discretization;

	// for Hybrid Max-Sum
	private Map<String, PiecewiseMultivariateQuadFunction> MSFunctionMapIOwn = new HashMap<>();
	private Set<AID> functionIOwn = new HashSet<>();
	private Set<AID> functionOwnedByOther = new HashSet<>();
	private Map<AID, MaxSumMessage> received_FUNCTION_TO_VARIABLE = new HashMap<>();
	private Map<AID, MaxSumMessage> received_VARIABLE_TO_FUNCTION = new HashMap<>();
	private Map<AID, MaxSumMessage> stored_FUNCTION_TO_VARIABLE = new HashMap<>();
	private Map<AID, MaxSumMessage> stored_VARIABLE_TO_FUNCTION = new HashMap<>();

	private Set<Double> currentValueSet = new HashSet<>();
	private Map<String, Double> neighborChosenValueMap = new HashMap<>();

	private int interpolationStepSize;

	public ContinuousDcopAgent() {
		isRoot = false;
		isLeaf = false;
		agentID = getLocalName();
	}

	// done with LS-RAND
	public void readArguments() {
		Object[] args = getArguments();
		out.println(Arrays.deepToString(args));

		// parameters for running experiments
		inputFileName = (String) args[0];
		algorithm = Integer.parseInt((String) args[1]);
		String a[] = inputFileName.replaceAll("rep_", "").replaceAll(".dzn", "").split("_d");
		instanceID = Integer.parseInt(a[0]);
		noAgent = Integer.parseInt(a[1]);

		gradientIteration = Integer.valueOf((String) args[2]);
		numberOfPoints = Integer.valueOf((String) args[3]);

		isApprox = true;
		interpolationStepSize = 100;
	}

	protected void setup() {
		readArguments();
		agentID = getLocalName();

		readMinizincFileThenParseNeighborAndConstraintTable(inputFileName, noAgent);

		out.println("This is agent " + getLocalName());

		if (Integer.valueOf(agentID) == rootFromInput) {
			isRoot = true;
			out.println("Agent " + agentID + " is the ROOT");
		}
		/***** START register neighbors with DF *****/
		registerWithDF();
		/***** END register neighbors with DF *****/

		// add constraints table FROM constraintTableWithoutRandomList TO
		// organizedConstraintTableList
		reorganizeConstaintTable();

		bean = ManagementFactory.getThreadMXBean();
		bean.setThreadContentionMonitoringEnabled(true);

		SequentialBehaviour mainSequentialBehaviourList = new SequentialBehaviour();
		mainSequentialBehaviourList.addSubBehaviour(new SEARCH_NEIGHBORS(this));
		mainSequentialBehaviourList.addSubBehaviour(new BROADCAST_RECEIVE_HEURISTIC_INFO(this));
		mainSequentialBehaviourList.addSubBehaviour(new PSEUDOTREE_GENERATION(this));

		currentValueSet = globalInterval.getMidPoints(numberOfPoints);

		if (isRunningDSA() && isRunningDiscreteAlg()) {
			createDCOPTableFromFunction(getFunctionMap());
			initializeDSAValue(currentValueSet);
		}

		if (isRunningDSA() && !isRunningDiscreteAlg()) {
			initializeDSAValue(currentValueSet);
		}

		// DPOP
		if (isRunningDPOP()) {
			mainSequentialBehaviourList.addSubBehaviour(new DPOP_UTIL(this));
			mainSequentialBehaviourList.addSubBehaviour(new DPOP_VALUE(this));
			mainSequentialBehaviourList.addSubBehaviour(new RECEIVE_SEND_UTIL_TO_ROOT(this));
		} // MAX-SUM
		else if (isRunningMaxsum()) {
			for (int i = 0; i < gradientIteration; i++) {
				mainSequentialBehaviourList.addSubBehaviour(new MAXSUM_VARIABLE_TO_FUNCTION(this));
				mainSequentialBehaviourList.addSubBehaviour(new MAXSUM_FUNCTION_TO_VARIABLE(this));
			}
			mainSequentialBehaviourList.addSubBehaviour(new DPOP_VALUE(this));
			mainSequentialBehaviourList.addSubBehaviour(new RECEIVE_SEND_UTIL_TO_ROOT(this));
		} else if (isRunningDSA() && isRunningDiscreteAlg()) {
			for (int i = 0; i < gradientIteration; i++) {
				mainSequentialBehaviourList.addSubBehaviour(new DISCRETE_DSA(this));
			}
			mainSequentialBehaviourList.addSubBehaviour(new RECEIVE_SEND_UTIL_TO_ROOT(this));
		} else if (isRunningDSA() && !isRunningDiscreteAlg()) {
			for (int i = 0; i < gradientIteration; i++) {
				mainSequentialBehaviourList.addSubBehaviour(new CONTINUOUS_DSA(this));
			}
			mainSequentialBehaviourList.addSubBehaviour(new DPOP_VALUE(this));
			mainSequentialBehaviourList.addSubBehaviour(new RECEIVE_SEND_UTIL_TO_ROOT(this));
		}

		mainSequentialBehaviourList.addSubBehaviour(new AGENT_TERMINATE(this));
		addBehaviour(mainSequentialBehaviourList);
	}

	private void initializeDSAValue(Set<Double> valueSet) {
		List<Double> clonedValueList = new ArrayList<>(valueSet);
		Collections.shuffle(clonedValueList);
		setValue(clonedValueList.get(0));
	}

	private boolean isRunningDSA() {
		return algorithm == DISCRETE_DSA || algorithm == CONTINUOUS_DSA;
	}

	public boolean isPrinting() {
		return agentID.equals("3") || agentID.equals("4");
	}

	// JADE function: stop the Agent
	protected void takeDown() {
		actualEndTime = System.currentTimeMillis();
		out.println("Agent " + agentID + " has RUNNING TIME: " + (actualEndTime - startTime) + "ms");
		out.println("Agent " + agentID + " with threadID " + Thread.currentThread().getId() + " has SIMULATED TIME: "
				+ simulatedTime / 1000000 + "ms");
		out.println("Agent " + agentID + " with threadID " + Thread.currentThread().getId() + " has sim TIME: "
				+ bean.getCurrentThreadUserTime() / 1000000 + "ms");
		System.err.println("Agent: " + getAID().getName() + " terminated.");
		try {
			DFService.deregister(this);
		} catch (FIPAException e) {
			e.printStackTrace();
		}
	}

	@SuppressWarnings("unused")
	private List<List<String>> getAllTupleValueOfGivenLabel(List<String> varLabel, boolean isDecVar) {
		List<List<String>> allTuple = new ArrayList<List<String>>();
		List<Integer> sizeDomainList = new ArrayList<Integer>();
		int totalSize = 1;
		for (String randVar : varLabel) {
			int domainSize = 0;

			if (isDecVar) {
				domainSize = decisionVariableDomainMap.get(randVar).size();
			}
//			else
//				domainSize = randomVariableDomainMap.get(randVar).size();
			sizeDomainList.add(domainSize);
			totalSize *= domainSize;
		}

		int noVar = varLabel.size();

		// go from 0 to totalSize
		for (int count = 0; count < totalSize; count++) {
			List<String> valueTuple = new ArrayList<String>();
			int quotient = count;
			// for each value count, decide the index of each column, then add to the tuple
			for (int varIndex = noVar - 1; varIndex >= 0; varIndex--) {
				int remainder = quotient % sizeDomainList.get(varIndex);
				quotient = quotient / sizeDomainList.get(varIndex);
				if (isDecVar)
					valueTuple.add(decisionVariableDomainMap.get(varLabel.get(varIndex)).get(remainder));
//				else 
//					valueTuple.add(randomVariableDomainMap.get(varLabel.get(varIndex)).get(remainder));
			}
			Collections.reverse(valueTuple);
			allTuple.add(valueTuple);
		}

		return allTuple;
	}

	void reorganizeConstaintTable() {
		// traverse each constraint table in constrainTableList
		// create new constraintTableList
		for (Table constraintTable : constraintTableWithoutRandomList) {
			organizedConstraintTableList.add(constraintTable);
		}
	}

	boolean isConstraintTableAtEachTSMapNull(int indexInConstraintTableMap) {
		List<Table> tableList = constraintTableAtEachTSMap.get(indexInConstraintTableMap);
		if (tableList == null)
			return true;
		else
			return false;
	}

	public void addConstraintTableToList(int timeStep) {
		// traverse table in organizedConstraintTableList
		for (Table decTable : organizedConstraintTableList) {
			List<String> decLabel = decTable.getLabel();
			// for each table, run time step from 0 to allowed
			// for (int tS=0; tS<=solveTimeStep; tS++) {
			Table newTable = new Table(decLabel);

			// at each timeStep, traverse rows
			for (Row row : decTable.getRowSet()) {
				double updatedUtility = 0;
				updatedUtility = row.getUtility();
				newTable.addRow(new Row(row.getValueList(), updatedUtility));
			}
			constraintTableAtEachTSMap.get(timeStep).add(newTable);
		}
	}

	boolean isArrayContainedInOtherArray(List<List<String>> bigArray, List<String> smallArray) {
		if (bigArray.size() == 0 || smallArray.size() == 0)
			return false;
		for (List<String> traversal : bigArray) {
			boolean isArrayFound = true;
			if (traversal.size() != smallArray.size()) {
				out.println("!!!!!!Different size!!!!!!");
				continue;
			}
			for (int i = 0; i < traversal.size(); i++) {
				if (traversal.get(i).equals(smallArray.get(i)) == false) {
					isArrayFound = false;
					break;
				}

			}
			if (isArrayFound == false)
				continue;
			return true;
		}

		return false;
	}

	public void sendObjectMessage(AID receiver, Object content, int msgCode) {
		ACLMessage message = new ACLMessage(msgCode);
		try {
			message.setContentObject((Serializable) content);
		} catch (IOException e) {
			e.printStackTrace();
		}
		message.addReceiver(receiver);
		send(message);
	}

	public void sendObjectMessage(AID receiver, Object content, int msgCode, long time) {
		ACLMessage message = new ACLMessage(msgCode);
		try {
			message.setContentObject((Serializable) content);
		} catch (IOException e) {
			e.printStackTrace();
		}
		message.addReceiver(receiver);
		message.setLanguage(String.valueOf(time));
		send(message);

//		out.println("Iteration " + getLsIteration() + " Agent " + agenID + " send message " + content + " to agent " + receiver.getLocalName());
	}

	public void sendObjectMessageWithIteration(AID receiver, Object content, int msgCode, int iteration, long time) {
		ACLMessage message = new ACLMessage(msgCode);
		try {
			message.setContentObject((Serializable) content);
		} catch (IOException e) {
			e.printStackTrace();
		}
		message.addReceiver(receiver);
		message.setLanguage(String.valueOf(time));
		message.setConversationId(String.valueOf(iteration));
		send(message);
	}

	public void sendByteObjectMessageWithTime(AID receiver, PiecewiseMultivariateQuadFunction content, int msgCode,
			long time) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		GZIPOutputStream gzipOut = new GZIPOutputStream(baos);
		ObjectOutputStream objectOut = new ObjectOutputStream(gzipOut);
		objectOut.writeObject(content);
		objectOut.close();
		byte[] data = baos.toByteArray();

		ACLMessage message = new ACLMessage(msgCode);
		message.setByteSequenceContent(data);
		message.addReceiver(receiver);
		message.setLanguage(String.valueOf(time));
		send(message);
	}

	public void printTree(boolean isRoot) {
		out.print("Agent " + agentID + " has children: ");
		for (AID childrenAID : childrenAIDList) {
			out.print(childrenAID.getLocalName() + " ");
		}
		out.print("Agent " + agentID + " has pseudo-children: ");
		for (AID pchildAID : pseudoChildrenAIDList) {
			out.print(pchildAID.getLocalName() + " ");
		}

		out.println();
	}

	private void readMinizincFileThenParseNeighborAndConstraintTable(String inputFileName, int numberOfAgents) {
		int maxNumberOfNeighbors = Integer.MIN_VALUE;

		final String DOMAIN = "domain";
		final String FUNCTION = "function";
		final String NEIGHBOR_SET = "neighbor set: ";

		try (BufferedReader br = new BufferedReader(
				new FileReader(System.getProperty("user.dir") + "/d" + numberOfAgents + "/" + inputFileName))) {
			List<String> lineWithSemiColonList = new ArrayList<String>();

			String line = br.readLine();
			while (line != null) {
				if (line.length() == 0 || line.startsWith("%") == true) {
					line = br.readLine();
					continue;
				}

				// concatenate line until meet ';'
				if (line.endsWith(";") == false) {
					do {
						line += br.readLine();
					} while (line.endsWith(";") == false);
				}

//				line = line.replace(" ","");
				line = line.replace(";", "");
				lineWithSemiColonList.add(line);
				line = br.readLine();
			}

			// Process line by line;
			for (String lineWithSemiColon : lineWithSemiColonList) {
				/** DOMAIN **/
				// domain 10
				if (lineWithSemiColon.startsWith(DOMAIN)) {
					lineWithSemiColon = lineWithSemiColon.replaceAll("domain ", "");
					int domainMax = Integer.parseInt(lineWithSemiColon);
					globalInterval = new Interval(-domainMax, domainMax);
				}

				/** FUNCTION */
				// function -281x_2^2 199x_2 -22x_0^2 252x_0 288x_2x_0 358;
				// BinaryFunction func = new BinaryFunction(-1, 20, -3, 40, -2, 6,
				// Double.valueOf(idStr), 1.0);
				if (lineWithSemiColon.startsWith(FUNCTION)) {
					String selfVar = "x_" + agentID;
					// x_1^ and x_10^
					if (!lineWithSemiColon.contains(selfVar + "^"))
						continue;

					lineWithSemiColon = lineWithSemiColon.replaceAll("function ", "");
					String[] termStrList = lineWithSemiColon.split(" ");
					double[] arr = parseFunction(termStrList, selfVar);
					String neighbor = String.valueOf((int) arr[6]);
					MultivariateQuadFunction func = new MultivariateQuadFunction(arr, agentID, neighbor);

					// Adding the new neighbor to neighborStrSet
					neighborStrSet.add(neighbor);

					PiecewiseMultivariateQuadFunction pwFunc = new PiecewiseMultivariateQuadFunction();
					// creating the interval map
					Map<String, Interval> intervalMap = new HashMap<>();
					intervalMap.put(agentID, globalInterval);
					intervalMap.put(neighbor, globalInterval);

					pwFunc.addToFunctionMapWithInterval(func, intervalMap, NOT_TO_OPTIMIZE_INTERVAL);
					functionMap.put(neighbor, pwFunc);

					// Own the function
					if (isRunningMaxsum() && compare(Double.valueOf(agentID), Double.valueOf(neighbor)) < 0) {
						// add the function to Maxsum function map
						// add the neighbor to external-var-agent-set
						MSFunctionMapIOwn.put(neighbor, pwFunc);
					}
				}
				if (lineWithSemiColon.startsWith(NEIGHBOR_SET)) {
					lineWithSemiColon = lineWithSemiColon.replace(NEIGHBOR_SET, "");
					lineWithSemiColon = lineWithSemiColon.replace("x_", "");
					String[] agentWithNeighbors = lineWithSemiColon.split(" ");
					// 3: 1 4 5
					if (agentWithNeighbors.length - 1 > maxNumberOfNeighbors) {
						maxNumberOfNeighbors = agentWithNeighbors.length - 1;
						rootFromInput = Integer.parseInt(agentWithNeighbors[0].replaceAll(":", ""));
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// function -281x_1^2 199x_1 -22x_10^2 252x_10 288x_1x_10 358;
	// where x_2 is selfAgent and x_0 is the other agent
	/**
	 * @param termArray from input file
	 * @param selfAgent x_idStr
	 * @return an array of coefficients. Make sure that the function reads its
	 *         coefficients first
	 */
	public double[] parseFunction(String[] termArray, String selfAgent) {
		double coeffArray[] = new double[7];
		Arrays.fill(coeffArray, Integer.MIN_VALUE);
		int neighborID = -1;
		for (String term : termArray) {
			// -281x_1^2
			if (term.contains(selfAgent + "^2")) {
				coeffArray[0] = Double.parseDouble(term.replace(selfAgent + "^2", ""));
			}
			// -22x_10^2
			else if (term.contains("^2")) {
				term = term.replace("^2", "");
				coeffArray[2] = Double.parseDouble(term.split("x_")[0]);
				// neighbor
				coeffArray[6] = Double.parseDouble(term.split("x_")[1]);
				neighborID = (int) coeffArray[6];
			}
			// constant 358
			else if (!term.contains("_")) {
				coeffArray[5] = Double.parseDouble(term);

			}
			// 288x_1x_10 OR 252x_10 OR 199x_1
			// split the "x_"
			// count for number of element
			// then comparing number
			// done
			else {
				String[] smallerTerms = term.split("x_");
				if (smallerTerms.length == 3) {
					coeffArray[4] = Double.parseDouble(smallerTerms[0]);
				} else {
					int variable = Integer.valueOf(smallerTerms[1]);
					if (variable == neighborID) {
						coeffArray[3] = Double.parseDouble(smallerTerms[0]);
					} else {
						coeffArray[1] = Double.parseDouble(smallerTerms[0]);
					}
				}
			}
		}
		return coeffArray;
	}

	/**
	 * Create the DCOP tables from agent.getCurrentValueSet();
	 */
	public void createDCOPTableFromFunction(Map<String, PiecewiseMultivariateQuadFunction> functionMap) {
		List<Table> tableList = new ArrayList<>();
		for (PiecewiseMultivariateQuadFunction pwFunction : functionMap.values()) {
			MultivariateQuadFunction func = pwFunction.getTheFirstFunction(); // there is only one function in pw at
																				// this time

			List<String> varListLabel = func.getVariableSet().stream().collect(Collectors.toList());
			Table tableFromFunc = new Table(varListLabel);

			// Always binary functions
			String variableOne = varListLabel.get(0);
			String variableTwo = varListLabel.get(1);

			for (Double valueOne : getCurrentValueSet()) {
				Map<String, Double> valueMap = new HashMap<>();
				List<Double> rowValueList = new ArrayList<>();
				rowValueList.add(valueOne);
				valueMap.put(variableOne, valueOne);
				for (double valueTwo : getCurrentValueSet()) {
					rowValueList.add(valueTwo);
					valueMap.put(variableTwo, valueTwo);
					Row newRow = new Row(new ArrayList<>(rowValueList), func.evaluateToValueGivenValueMap(valueMap));
					tableFromFunc.addRow(newRow);
					rowValueList.remove(1);
					valueMap.remove(variableTwo);
				}
				rowValueList.clear();
				valueMap.clear();
			}
			tableList.add(tableFromFunc);
		}
		setTableList(tableList);
	}

	public void registerWithDF() {
		DFAgentDescription dfd = new DFAgentDescription();
		dfd.setName(getAID());
		for (String neighbor : neighborStrSet) {
			ServiceDescription sd = new ServiceDescription();
			// provide service for neighbor
			// later on, the neighbor will search for agent providing the service with this
			// neighbor's name
			sd.setType(neighbor);
			sd.setName(agentID);
			dfd.addServices(sd);
		}
		try {
			DFService.register(this, dfd);
		} catch (FIPAException fe) {
			fe.printStackTrace();
		}
	}

	/**
	 * Calculate the actual utility by ignoring the agent view
	 * 
	 * @return
	 */
	public double utilityFrom_TABLE_WithParentAndPseudo() {
		double sumUtility = 0;
		for (Table constraintTable : tableList) {
			List<String> decVarList = constraintTable.getLabel();
			List<Double> decValueList = new ArrayList<Double>();

			for (String agentInList : decVarList) {
				if (agentInList.equals(agentID))
					decValueList.add(value);
				else
					decValueList.add(neighborChosenValueMap.get(agentInList));
			}

			sumUtility += constraintTable.getUtilityGivenDecValueList(decValueList);
		}

		return sumUtility;
	}

	public double utilityFrom_FUNCTION_WithParentAndPseudo() {
		double sumUtility = 0;

		for (Entry<String, PiecewiseMultivariateQuadFunction> functionEntry : pwFunctionWithPParentMap.entrySet()) {
			String pParent = functionEntry.getKey();
			PiecewiseMultivariateQuadFunction function = functionEntry.getValue();

			Map<String, Double> valueMap = new HashMap<>();
			valueMap.put(agentID, value);
			valueMap.put(pParent, neighborChosenValueMap.get(pParent));

			sumUtility += function.getTheFirstFunction().evaluateToValueGivenValueMap(valueMap);
		}

		return sumUtility;
	}

	public boolean isRunningDPOP() {
		return algorithm == EF_DPOP || algorithm == DPOP || algorithm == AF_DPOP || algorithm == CAF_DPOP;
	}

	public boolean isRunningMaxsum() {
		return algorithm == MAXSUM || algorithm == HYBRID_MAXSUM || algorithm == CAF_MAXSUM;
	}

	public boolean isClustering() {
		return algorithm == CAF_DPOP || algorithm == CAF_MAXSUM;
	}

	public boolean isRunningDiscreteAlg() {
		return algorithm == DPOP || algorithm == MAXSUM || algorithm == DISCRETE_DSA;
	}

	public boolean isRunningHybridAlg() {
		return algorithm == AF_DPOP || algorithm == CAF_DPOP || algorithm == HYBRID_MAXSUM || algorithm == CAF_MAXSUM;
	}

	public boolean isRunningHybridDPOP() {
		return algorithm == AF_DPOP || algorithm == CAF_DPOP;
	}

	public String getID() {
		return agentID;
	}

	public void setAgentStrID(String idStr) {
		this.agentID = idStr;
	}

	public boolean isRoot() {
		return isRoot;
	}

	public void setRoot(boolean isRoot) {
		this.isRoot = isRoot;
	}

	public boolean isLeaf() {
		return isLeaf;
	}

	public void setLeaf(boolean isLeaf) {
		this.isLeaf = isLeaf;
	}

	public Set<AID> getNeighborAIDSet() {
		return neighborAIDSet;
	}

	public void setNeighborAIDSet(Set<AID> neighborAIDSet) {
		this.neighborAIDSet = neighborAIDSet;
	}

	public Set<String> getNeighborStrSet() {
		return neighborStrSet;
	}

	public void setNeighborStrSet(Set<String> neighborStrSet) {
		this.neighborStrSet = neighborStrSet;
	}

	public Map<AID, Integer> getConstraintInfoMap() {
		return constraintInfoMap;
	}

	public void setConstraintInfoMap(Map<AID, Integer> constraintInfoMap) {
		this.constraintInfoMap = constraintInfoMap;
	}

	public boolean isNotVisited() {
		return notVisited;
	}

	public void setNotVisited(boolean notVisited) {
		this.notVisited = notVisited;
	}

	public List<AID> getChildrenAIDList() {
		return childrenAIDList;
	}

	public void setChildrenAIDList(List<AID> childrenAIDList) {
		this.childrenAIDList = childrenAIDList;
	}

	public AID getParentAID() {
		return parentAID;
	}

	public void setParentAID(AID parentAID) {
		this.parentAID = parentAID;
	}

	public List<String> getParentAndPseudoStrList() {
		return parentAndPseudoStrList;
	}

	public void setParentAndPseudoStrList(List<String> parentAndPseudoStrList) {
		this.parentAndPseudoStrList = parentAndPseudoStrList;
	}

	public List<AID> getPseudoChildrenAIDList() {
		return pseudoChildrenAIDList;
	}

	public void setPseudoChildrenAIDList(List<AID> pseudoChildrenAIDList) {
		this.pseudoChildrenAIDList = pseudoChildrenAIDList;
	}

	public List<AID> getPseudoParentAIDList() {
		return pseudoParentAIDList;
	}

	public void setPseudoParentAIDList(List<AID> pseudoParentAIDList) {
		this.pseudoParentAIDList = pseudoParentAIDList;
	}

	public ThreadMXBean getBean() {
		return bean;
	}

	public void setBean(ThreadMXBean bean) {
		this.bean = bean;
	}

	public long getCurrentStartTime() {
		return currentStartTime;
	}

	/**
	 * Set currentStartTime
	 */
	public void startSimulatedTiming() {
		setCurrentStartTime(getBean().getCurrentThreadUserTime());
	}

	/**
	 * Compute the simulated runtime up to this point
	 */
	public void pauseSimulatedTiming() {
		setSimulatedTime(getSimulatedTime() + getBean().getCurrentThreadUserTime() - getCurrentStartTime());
	}

	public void setCurrentStartTime(long currentStartTime) {
		this.currentStartTime = currentStartTime;
	}

	public Map<String, List<String>> getDecisionVariableDomainMap() {
		return decisionVariableDomainMap;
	}

	public void setDecisionVariableDomainMap(HashMap<String, List<String>> decisionVariableDomainMap) {
		this.decisionVariableDomainMap = decisionVariableDomainMap;
	}

	public Map<Integer, Double> getValueAtEachTSMap() {
		return valueAtEachTSMap;
	}

	public void setValueAtEachTSMap(HashMap<Integer, Double> valueAtEachTSMap) {
		this.valueAtEachTSMap = valueAtEachTSMap;
	}

	public long getSimulatedTime() {
		return simulatedTime;
	}

	public void setSimulatedTime(long simulatedTime) {
		this.simulatedTime = simulatedTime;
	}

	public void addupSimulatedTime(long time) {
		this.simulatedTime += time;
	}

	public List<Table> getTableList() {
		return tableList;
	}

	public void setTableList(List<Table> tableList) {
		this.tableList = tableList;
	}

	public Map<Integer, List<Table>> getConstraintTableAtEachTSMap() {
		return constraintTableAtEachTSMap;
	}

	public void setConstraintTableAtEachTSMap(HashMap<Integer, List<Table>> constraintTableAtEachTSMap) {
		this.constraintTableAtEachTSMap = constraintTableAtEachTSMap;
	}

	public Table getCollapsedSwitchingCostTable() {
		return collapsedSwitchingCostTable;
	}

	public void setCollapsedSwitchingCostTable(Table collapsedSwitchingCostTable) {
		this.collapsedSwitchingCostTable = collapsedSwitchingCostTable;
	}

	public int getCurrentTS() {
		return currentTS;
	}

	public void setCurrentTS(int currentTS) {
		this.currentTS = currentTS;
	}

	public void incrementCurrentTS() {
		this.currentTS++;
	}

	public Table getAgentViewTable() {
		return agentViewTable;
	}

	public void setAgentViewTable(Table agentViewTable) {
		this.agentViewTable = agentViewTable;
	}

	public double getValue() {
		return value;
	}

	public void setValue(double value) {
		this.value = value;
	}

	public List<Double> getCurrentGlobalUtilityList() {
		return currentGlobalUtilityList;
	}

	public void setCurrentGlobalUtilityList(List<Double> currentGlobalUtilityList) {
		this.currentGlobalUtilityList = currentGlobalUtilityList;
	}

	public double getCurrentGlobalUtility() {
		return currentGlobalUtility;
	}

	public void setCurrentGlobalUtility(double currentGlobalUtility) {
		this.currentGlobalUtility = currentGlobalUtility;
	}

	public double getTotalGlobalUtility() {
		return totalGlobalUtility;
	}

	public void setTotalGlobalUtility(double totalGlobalUtility) {
		this.totalGlobalUtility = totalGlobalUtility;
	}

	/**
	 * @return the time needed to send the message
	 */
	public static long getDelayMessageTime() {
		return delayMessageTime;
	}

	public static void setDelayMessageTime(long delayMessageTime) {
		ContinuousDcopAgent.delayMessageTime = delayMessageTime;
	}

	public double getUtilFromChildren() {
		return utilFromChildren;
	}

	public void setUtilFromChildren(double utilFromChildren) {
		this.utilFromChildren = utilFromChildren;
	}

	public void addtoUtilFromChildren(double util) {
		this.utilFromChildren += util;
	}

	public Map<String, Double> getNeighborValueMap() {
		return neighborValueMap;
	}

	public void setNeighborValueMap(Map<String, Double> agentView_DPOP_TSMap) {
		this.neighborValueMap = agentView_DPOP_TSMap;
	}

	public long getEndTime() {
		return actualEndTime;
	}

	public void setEndTime(long endTime) {
		this.actualEndTime = endTime;
	}

	public long getStartTime() {
		return startTime;
	}

	public void setStartTime(long startTime) {
		this.startTime = startTime;
	}

	public int getLsIteration() {
		return lsIteration;
	}

	public void setLsIteration(int lsIteration) {
		this.lsIteration = lsIteration;
	}

	public void incrementLsIteration() {
		this.lsIteration++;
	}

	public List<String> getBestImproveValueList() {
		return bestImproveValueList;
	}

	public void setBestImproveValueList(List<String> bestImproveValueList) {
		this.bestImproveValueList = bestImproveValueList;
	}

	public double getAggregatedUtility() {
		return aggregatedUtility;
	}

	public void setAggregatedUtility(double aggregatedUtility) {
		this.aggregatedUtility = aggregatedUtility;
	}

	public Map<String, Double> getValuesToSendInVALUEPhase() {
		return valuesToSendInVALUEPhase;
	}

	public void setValuesToSendInVALUEPhase(HashMap<String, Double> valuesToSendInVALUEPhase) {
		this.valuesToSendInVALUEPhase = valuesToSendInVALUEPhase;
	}

	public void addValuesToSendInVALUEPhase(String agent, Double value) {
		this.valuesToSendInVALUEPhase.put(agent, value);
	}

	public Map<Integer, String> getPickedRandomMap() {
		return pickedRandomMap;
	}

	public void setPickedRandomMap(HashMap<Integer, String> pickedRandomMap) {
		this.pickedRandomMap = pickedRandomMap;
	}

	public void addPickedRandomMap(Integer timeStep, String pickedRandomValue) {
		this.pickedRandomMap.put(timeStep, pickedRandomValue);
	}

	public String getPickedRandomAt(Integer timeStep) {
		return this.pickedRandomMap.get(timeStep);
	}

	public Map<String, PiecewiseMultivariateQuadFunction> getFunctionMap() {
		return functionMap;
	}

	public void setFunctionMap(Map<String, PiecewiseMultivariateQuadFunction> functionMap) {
		this.functionMap = functionMap;
	}

	public PiecewiseMultivariateQuadFunction getAgentViewFunction() {
		return agentViewFunction;
	}

	public void setAgentViewFunction(PiecewiseMultivariateQuadFunction agentViewFunction) {
		this.agentViewFunction = agentViewFunction;
	}

	public Map<String, PiecewiseMultivariateQuadFunction> getPWFunctionWithPParentMap() {
		return pwFunctionWithPParentMap;
	}

	public void setPWFunctionWithPParentMap(Map<String, PiecewiseMultivariateQuadFunction> pwFunctionWithPParentMap) {
		this.pwFunctionWithPParentMap = pwFunctionWithPParentMap;
	}

	public int getNumberOfApproxAgents() {
		return numberOfApproxAgents;
	}

	public void setNumberOfApproxAgents(int numberOfAgents) {
		this.numberOfApproxAgents = numberOfAgents;
	}

	public static Interval getGlobalInterval() {
		return globalInterval;
	}

	public static void setGlobalInterval(Interval interval) {
		globalInterval = interval;
	}

	public boolean isApprox() {
		return isApprox;
	}

	public void setApprox(boolean isApprox) {
		this.isApprox = isApprox;
	}

	/**
	 * Return getMidPointInIntegerRanges() if clustering Return
	 * initializeDiscretization(numberOfPoints) if NOT clustering
	 * 
	 * @return
	 */
	public Set<Double> getCurrentValueSet() {
		return currentValueSet;
	}

	public void setCurrentValueSet(Set<Double> currentValueSet) {
		this.currentValueSet = currentValueSet;
	}

	public Map<String, PiecewiseMultivariateQuadFunction> getMSFunctionMapIOwn() {
		return MSFunctionMapIOwn;
	}

	public void setMSFunctionMapIOwn(Map<String, PiecewiseMultivariateQuadFunction> MSFunctionMapIOwn) {
		this.MSFunctionMapIOwn = MSFunctionMapIOwn;
	}

	public Set<AID> getFunctionIOwn() {
		return functionIOwn;
	}

	public void setFunctionIOwn(Set<AID> functionIOwn) {
		this.functionIOwn = functionIOwn;
	}

	public void addAgentToFunctionIOwn(AID insideAgent) {
		this.functionIOwn.add(insideAgent);
	}

	public Set<AID> getFunctionOwnedByOther() {
		return functionOwnedByOther;
	}

	public void setFunctionOwnedByOther(Set<AID> functionOwnedByOther) {
		this.functionOwnedByOther = functionOwnedByOther;
	}

	public void addAgentToFunctionOwnedByOther(AID outsideAgent) {
		this.functionOwnedByOther.add(outsideAgent);
	}

	public Map<AID, MaxSumMessage> getReceived_FUNCTION_TO_VARIABLE() {
		return received_FUNCTION_TO_VARIABLE;
	}

	public void setReceived_FUNCTION_TO_VARIABLE(Map<AID, MaxSumMessage> received_FUNCTION_TO_VARIABLE) {
		this.received_FUNCTION_TO_VARIABLE = received_FUNCTION_TO_VARIABLE;
	}

	public Map<AID, MaxSumMessage> getReceived_VARIABLE_TO_FUNCTION() {
		return received_VARIABLE_TO_FUNCTION;
	}

	public void setReceived_VARIABLE_TO_FUNCTION(Map<AID, MaxSumMessage> received_VARIABLE_TO_FUNCTION) {
		this.received_VARIABLE_TO_FUNCTION = received_VARIABLE_TO_FUNCTION;
	}

	public Map<AID, MaxSumMessage> getStored_FUNCTION_TO_VARIABLE() {
		return stored_FUNCTION_TO_VARIABLE;
	}

	public void setStored_FUNCTION_TO_VARIABLE(Map<AID, MaxSumMessage> stored_FUNCTION_TO_VARIABLE) {
		this.stored_FUNCTION_TO_VARIABLE = stored_FUNCTION_TO_VARIABLE;
	}

	public Map<AID, MaxSumMessage> getStored_VARIABLE_TO_FUNCTION() {
		return stored_VARIABLE_TO_FUNCTION;
	}

	public void setStored_VARIABLE_TO_FUNCTION(Map<AID, MaxSumMessage> stored_VARIABLE_TO_FUNCTION) {
		this.stored_VARIABLE_TO_FUNCTION = stored_VARIABLE_TO_FUNCTION;
	}

	public Map<String, Double> getNeighborChosenValueMap() {
		return neighborChosenValueMap;
	}

	public void setNeighborChosenValueMap(Map<String, Double> neighborChosenValueMap) {
		this.neighborChosenValueMap = neighborChosenValueMap;
	}

	public static int getGradientIteration() {
		return gradientIteration;
	}

	public int getDiscretization() {
		return discretization;
	}

	public void setDiscretization(int discretization) {
		this.discretization = discretization;
	}

	public static int getNumberOfPoints() {
		return numberOfPoints;
	}

	public int getDomainSize() {
		return globalInterval.getIncrementRange();
	}

	public int getInterpolationStepSize() {
		return interpolationStepSize;
	}

	public void setInterpolationStepSize(int interpolationStepSize) {
		this.interpolationStepSize = interpolationStepSize;
	}

	public static int getAlgorithm() {
		return algorithm;
	}

	public static int getInstanceID() {
		return instanceID;
	}

	public static int getNoAgent() {
		return noAgent;
	}
}