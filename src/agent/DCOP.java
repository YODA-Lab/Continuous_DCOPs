package agent; //dcop

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import behaviour.AGENT_TERMINATE;
import behaviour.BROADCAST_RECEIVE_HEURISTIC_INFO;
import behaviour.DPOP_UTIL;
import behaviour.PSEUDOTREE_GENERATION;

import behaviour.SEARCH_NEIGHBORS;
import function.Interval;
import function.multivariate.MultivariateQuadFunction;
import function.multivariate.PiecewiseMultivariateQuadFunction;
//import function.binary.PiecewiseFunction;
//import function.binary.QuadraticBinaryFunction;
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

/* Each agent is a node in the graph
 * The graph is presented as pseudo-tree
 * Pseudo-tree is generated by Distributed DFS
 * 
 * Each Agent has a "root" flag to indicate root or not
 * Each Agent has a local name which is an ID number and is assigned by constructor.
 * Based on the ID number, each agent has a fixed number neighbors, which is instantiated
 * by the constructor
 * 
 * ****PROCESS OF FINDING AID NEIGHBORS
 * Each Agent has a list of neighbors, and register his neighbors ID number to DF
 * Each Agent find his neighbors' AIDs by searching DF which agents register the Agent's ID numbers as
 * a neighbor. The add to his list of AID neighbors.
 * 
 * The process of finding AIDs only stops when the number of recognized AID is the number of his neighbors
 * 
 * ****PROCESS OF GENERATING PSEUDOTREE
 * Each agent always listens to the first messages, while trying to finish the searching process
 * After finishing the searching process, he begins to process the messages and send the message to his
 * neighbors.
 * 
 * Agent root start sending messages when finish searching agents.
 * 
 * Each agents will print out his parent, his children, his pseudo-parents, his pseudo-children
 * 
 * ****PROCESS OF DPOP
 * DPOP starts when the PSEUDOTREE PROCESS FINISHED
 */
public class DCOP extends Agent implements DcopInfo {
	
	private static final long serialVersionUID = 2919994686894853596L;
	
  public int algorithm;
  public int h;   //timeStep where the Markov chain is converged
  public String inputFileName;
	public String varDecisionFileName;
	public int domainMax; //from 0 - domain
	public Interval globalInterval;

	private Map<String, List<Double>> agentView_DPOP_TSMap;

	private String idStr;
	private boolean isRoot;
	private boolean isLeaf;
	
	private AID	parentAID;
	private List<AID> childrenAIDList;
	private List<AID> neighborAIDList; 
	private List<AID> pseudoParentAIDList;
	private List<AID> pseudoChildrenAIDList;
	private List<String> parentAndPseudoStrList;
	private List<String> neighborStrList;
//	private List<String> childrenStrList;
	
	private List<Table> currentTableListDPOP;
	private List<Table> constraintTableWithoutRandomList;
	private List<Table> organizedConstraintTableList;
	
	private HashMap<String, List<String>> decisionVariableDomainMap;
	//map TS -> constraint table list (if local_search)
	//map TS -> 1 collapsed table list (if collapsed dpop)
	private HashMap<Integer, List<Table>> constraintTableAtEachTSMap;
	private Table collapsedSwitchingCostTable;
	
	//VALUE phase
	HashMap<String, Double> valuesToSendInVALUEPhase;
	
	//used for LOCAL SEARCH
	private HashMap<Integer, Double> valueAtEachTSMap;
	//List<Double> utilityAtEachTSList;
	//agent -> <values0, values1, ..., values_n>
  private List<Double> currentGlobalUtilityList;
  private List<String> bestImproveValueList;
  private double currentGlobalUtility;
  private double totalGlobalUtility;
  private double utilFromChildrenLS;
	
  private Table agentViewTable;
  private Double chosenValue;
  private HashMap<Integer, String> pickedRandomMap;

	private int currentTS;
	
  private long startTime;
  private long endTime;
  private long currentUTILstartTime;
	private int lsIteration;

	//simulated time
	private ThreadMXBean bean;
  private long simulatedTime = 0;
  private long currentStartTime;
  private static long delayMessageTime = 0;
  
  //for reuse information
  private HashMap<AID, Integer> constraintInfoMap;
  private boolean notVisited = true;
	
//	List<String> neighborWithRandList;
	
  private double oldLSUtility = 0;
	private double oldLSRunningTime = 0; //old running time because compare to see if old iteration is converged
	private boolean stop = false;

	private double utilityAndCost;
	private String lastLine;
	
	private List<PiecewiseMultivariateQuadFunction> functionList;
	private PiecewiseMultivariateQuadFunction agentViewFunction;
	private List<PiecewiseMultivariateQuadFunction> currentFunctionListDPOP;
	
	private int numberOfIntervals;

	// for writing output purposes
	public int instanceID;
	public int noAgent;
	
	public DCOP() {
		initializeArguments();
		isRoot = false;
		isLeaf = false;
		totalGlobalUtility = 0;
		lsIteration = 0;
		utilFromChildrenLS = 0;
		currentTS = 0;
		functionList = new ArrayList<>();
    idStr = getLocalName();
    h = 0;
	}
	
	//done with LS-RAND
	public void readArguments() {
	  Object[] args = getArguments();
		//parameters for running experiments
		algorithm = DPOP;
		inputFileName = (String) args[0];
    System.out.println(Arrays.deepToString(args));
    numberOfIntervals = Integer.parseInt((String) args[1]);
		String a[] = inputFileName.replaceAll("rep_","").replaceAll(".dzn","").split("_d");
		instanceID = Integer.parseInt(a[0]);
    noAgent = Integer.parseInt(a[0]);	}
	
  protected void setup() {
    readArguments();
    idStr = getLocalName();
    if (idStr.equals("1")) {
      isRoot = true;
    }

		readMinizincFileThenParseNeighborAndConstraintTable(inputFileName);		
		/***** START register neighbors with DF *****/ 
		registerWithDF();
		/***** END register neighbors with DF *****/ 
				
		// add constraints table FROM constraintTableWithoutRandomList TO organizedConstraintTableList
		reorganizeConstaintTable();
		
		if (algorithm == DSA) {
//		    createNonProcessTable();
//		    createProcessedTable();
		}

		if (algorithm == DPOP) {
//			addExpectedRandomTableToListAllTS();
//			addConstraintTableToListAllTS();
		}		
		else if (algorithm == DSA) {
//			addConstraintTableToListAllTS();
		}
		

		startTime = System.currentTimeMillis();
		bean = ManagementFactory.getThreadMXBean();
		bean.setThreadContentionMonitoringEnabled(true);
		
		SequentialBehaviour mainSequentialBehaviourList = new SequentialBehaviour();
		mainSequentialBehaviourList.addSubBehaviour(new SEARCH_NEIGHBORS(this));
		mainSequentialBehaviourList.addSubBehaviour(new BROADCAST_RECEIVE_HEURISTIC_INFO(this));
		mainSequentialBehaviourList.addSubBehaviour(new PSEUDOTREE_GENERATION(this));
		
		//run DPOP multi-step
		if (algorithm == DPOP) {
			mainSequentialBehaviourList.addSubBehaviour(new DPOP_UTIL(this));
//			mainSequentialBehaviourList.addSubBehaviour(new DPOP_VALUE(this));
		}
		
		mainSequentialBehaviourList.addSubBehaviour(new AGENT_TERMINATE(this));
		addBehaviour(mainSequentialBehaviourList); 
	}
	
	//JADE function: stop the Agent
	protected void takeDown() {	
		endTime = System.currentTimeMillis();
		System.out.println("Agent " + idStr + " has RUNNING TIME: " + (endTime - startTime) + "ms");
		System.out.println("Agent " + idStr + " with threadID " + Thread.currentThread().getId() + 
								" has SIMULATED TIME: " + simulatedTime/1000000 + "ms");
		System.out.println("Agent " + idStr + " with threadID " + Thread.currentThread().getId() + 
				" has sim TIME: " + bean.getCurrentThreadUserTime()/1000000 + "ms");
		System.err.println("Agent: " + getAID().getName() + " terminated.");
		try {
			DFService.deregister(this);
		} catch (FIPAException e) {
			e.printStackTrace();
		}
	}

	public void initializeArguments() {
		neighborStrList = new ArrayList<>();
		neighborAIDList = new ArrayList<>();
		childrenAIDList = new ArrayList<>();
		pseudoParentAIDList = new ArrayList<>();
		pseudoChildrenAIDList = new ArrayList<>();
		parentAndPseudoStrList = new ArrayList<>();
		currentTableListDPOP = new ArrayList<>();
		constraintTableWithoutRandomList = new ArrayList<>();
		organizedConstraintTableList = new ArrayList<>();
		decisionVariableDomainMap = new HashMap<String, List<String>>();
		constraintTableAtEachTSMap = new HashMap<Integer, List<Table>>();
		valueAtEachTSMap = new HashMap<Integer, Double>();

		agentView_DPOP_TSMap = new HashMap<String, List<Double>>();

		currentGlobalUtilityList = new ArrayList<>();
		bestImproveValueList = new ArrayList<String>();
		constraintInfoMap = new HashMap<AID, Integer>();
		valuesToSendInVALUEPhase = new HashMap<String, Double>();
		pickedRandomMap = new HashMap<Integer, String>();
		lastLine = "";
	}
	
	@SuppressWarnings("unused")
  private List<List<String>> getAllTupleValueOfGivenLabel(List<String> varLabel, boolean isDecVar) {
		List<List<String>> allTuple = new ArrayList<List<String>>();
		List<Integer> sizeDomainList = new ArrayList<Integer>();
		int totalSize = 1;
		for (String randVar:varLabel) {
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
		
		//go from 0 to totalSize
		for (int count=0; count<totalSize; count++) {
			List<String> valueTuple = new ArrayList<String>();
			int quotient = count;
			//for each value count, decide the index of each column, then add to the tuple
			for (int varIndex = noVar-1; varIndex>=0; varIndex--) {
				int remainder = quotient%sizeDomainList.get(varIndex);
				quotient = quotient/sizeDomainList.get(varIndex);
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
		//traverse each constraint table in constrainTableList
		//create new constraintTableList
		for (Table constraintTable:constraintTableWithoutRandomList) {
			organizedConstraintTableList.add(constraintTable);
		}
	}
	
	boolean isConstraintTableAtEachTSMapNull(int indexInConstraintTableMap) {
		List<Table> tableList = constraintTableAtEachTSMap.get(indexInConstraintTableMap);
		if (tableList == null)	return true;
		else	
			return false;
	}
	
	public void addConstraintTableToList(int timeStep) {
		// traverse table in organizedConstraintTableList
		for (Table decTable : organizedConstraintTableList) {
			List<Double> decLabel = decTable.getDecVarLabel();
			// for each table, run time step from 0 to allowed
			// for (int tS=0; tS<=solveTimeStep; tS++) {
			Table newTable = new Table(decLabel);

			// at each timeStep, traverse rows
			for (Row row : decTable.getTable()) {
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
		for (List<String> traversal:bigArray) {
			boolean isArrayFound = true;
			if (traversal.size() != smallArray.size()) {
				System.out.println("!!!!!!Different size!!!!!!");
				continue;
			}
			for (int i=0; i<traversal.size(); i++) {
				if (traversal.get(i).equals(smallArray.get(i)) == false) {
					isArrayFound = false;
					break;
				}
					
			}
			if (isArrayFound == false)	continue;
			return true;
		}
		
		return false;
	}

	double getUtilityFromTableGivenDecAndRand(Table table, List<Double> decValueList, List<Double> randIterationValue) {
		List<Row> tableToTraversed = table.getTable();
		for (Row row:tableToTraversed) {
			boolean isRowFound = true;
			//System.err.println("Utility of this row " + row.getUtility());
			List<Double> rowValueList = row.getValueList();
			List<Double> rowRandomList= row.getRandomList();

			if (rowValueList.size() != decValueList.size() || rowRandomList.size() != randIterationValue.size()) {
				System.err.println("!!!!!!Different size!!!!!!!!!");
				System.err.println("!!!!!!Recheck your code!!!!!!");
			}
			for (int index=0; index<decValueList.size(); index++) {
				if (rowValueList.get(index).equals(decValueList.get(index)) == false) {
					isRowFound = false;
					break;
				}
			}
			
			if (isRowFound == false)	continue;
			
			for (int index=0; index<randIterationValue.size(); index++) {
				if (rowRandomList.get(index).equals(randIterationValue.get(index)) == false) {
					isRowFound = false;
					break;
				}
			}
			
			if (isRowFound == false)	continue;
			
			return row.getUtility();
		}
		System.out.println("Not found!!!!!!!!!!!!!!");
		return Integer.MIN_VALUE;
	}

	@SuppressWarnings("unused")
  private List<Double> gaussian(double arr[][], int N) {
		List<Double> longtermUtilityList = new ArrayList<Double>();
		// take each line as pivot, except for the last line
		for (int pivotIndex = 0; pivotIndex < N - 1; pivotIndex++) {
			// go from the line below line pivotIndex, to the last line
			boolean isNotZeroRowFound = false;
			if (arr[pivotIndex][pivotIndex] == 0) {
				int notZeroRow;
				for (notZeroRow = pivotIndex + 1; notZeroRow < N; notZeroRow++) {
					if (arr[notZeroRow][pivotIndex] != 0) {
						isNotZeroRowFound = true;
						break;
					}
				}

				if (isNotZeroRowFound) {
					// swap row pivotIndex and row notZeroRow
					for (int columnToSwapIndex = 0; columnToSwapIndex < N + 1; columnToSwapIndex++) {
						double tempForSwap = arr[pivotIndex][columnToSwapIndex];
						arr[pivotIndex][columnToSwapIndex] = arr[notZeroRow][columnToSwapIndex];
						arr[notZeroRow][columnToSwapIndex] = tempForSwap;
					}
				} else {
					continue;
				}
			}

			for (int rowForGauss = pivotIndex + 1; rowForGauss < N; rowForGauss++) {
				double factor = arr[rowForGauss][pivotIndex]
						/ arr[pivotIndex][pivotIndex];
				for (int columnForGauss = 0; columnForGauss < N + 1; columnForGauss++) {
					arr[rowForGauss][columnForGauss] = arr[rowForGauss][columnForGauss]
							- factor * arr[pivotIndex][columnForGauss];
				}
			}
		}

		for (int columnPivot = N - 1; columnPivot >= 1; columnPivot--) {
			for (int rowAbovePivot = columnPivot - 1; rowAbovePivot >= 0; rowAbovePivot--) {
				double fraction = arr[rowAbovePivot][columnPivot]
						/ arr[columnPivot][columnPivot];
				for (int columnInTheRow = 0; columnInTheRow < N + 1; columnInTheRow++)
					arr[rowAbovePivot][columnInTheRow] = arr[rowAbovePivot][columnInTheRow]
							- fraction * arr[columnPivot][columnInTheRow];
			}
		}
		
		for (int i=0; i<N; i++) { 
			longtermUtilityList.add(arr[i][N]/arr[i][i]); 
		}
		
		return longtermUtilityList;
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
	
	public void sendObjectMessageWithTime(AID receiver, Object content, int msgCode, long time) {
		ACLMessage message = new ACLMessage(msgCode);
		try {
			message.setContentObject((Serializable) content);
		} catch (IOException e) {
			e.printStackTrace();
		}
		message.addReceiver(receiver);
		message.setLanguage(String.valueOf(time));
		send(message);
	}


	@SuppressWarnings("unused")
    private void printTree(boolean isRoot) {
		System.out.println("************");
		System.out.println("My ID is: " + idStr);
		if (isRoot == false)
			System.out.println("My parent is: " + parentAID.getLocalName());
		System.out.println("My children are: ");
		for (int i=0;i<childrenAIDList.size();i++) {
			System.out.print(childrenAIDList.get(i).getLocalName() + " ");
		}
		System.out.println();
		
		System.out.println("My pseudo_parents are: ");
		for (int i=0;i<pseudoParentAIDList.size();i++) {
			System.out.print(pseudoParentAIDList.get(i).getLocalName() + " ");
		}
		System.out.println();
		
		System.out.println("My pseudo_children are: ");
		for (int i=0;i<pseudoChildrenAIDList.size();i++) {
			System.out.print(pseudoChildrenAIDList.get(i).getLocalName() + " ");
		}
		System.out.println();
		
	}
	
	public void readMinizincFileThenParseNeighborAndConstraintTable(String inputFileName) {
		final String DOMAIN = "domain";
		final String FUNCTION = "function";
		
		try (BufferedReader br = new BufferedReader(new FileReader(
				System.getProperty("user.dir") + '/' + inputFileName))) {
			List<String> lineWithSemiColonList = new ArrayList<String>();
			
			String line = br.readLine();
			while (line != null) {
				if (line.length() == 0 || line.startsWith("%") == true) {
					line = br.readLine();
					continue;
				}
				
				//concatenate line until meet ';'
				if (line.endsWith(";") == false) {
					do {
						line += br.readLine();
					} while (line.endsWith(";") == false);
				}
				
//				line = line.replace(" ","");
				line = line.replace(";","");
				lineWithSemiColonList.add(line);
				line = br.readLine();
			}
			
			//Process line by line;
			for (String lineWithSemiColon:lineWithSemiColonList) {
				/**DOMAIN**/
			  //domain 10
				if (lineWithSemiColon.startsWith(DOMAIN)) {
				    lineWithSemiColon = lineWithSemiColon.replaceAll("domain ", "");
				    domainMax = Integer.parseInt(lineWithSemiColon);
				    globalInterval = new Interval(-domainMax, domainMax);
				}
				
				/**FUNCTION*/
				//function -281x_2^2 199x_2 -22x_0^2 252x_0 288x_2x_0 358;
				//BinaryFunction func = new BinaryFunction(-1, 20, -3, 40, -2, 6, Double.valueOf(idStr), 1.0);
				if (lineWithSemiColon.startsWith(FUNCTION)) {
				    String selfVar = "x_" + idStr;
				    if (!lineWithSemiColon.contains(selfVar)) continue;
	                System.out.println("Agent " + idStr + " line " + lineWithSemiColon);
				    
				    lineWithSemiColon = lineWithSemiColon.replaceAll("function ", "");
				    String[] termStrList = lineWithSemiColon.split(" ");
				    int[] arr = parseFunction(termStrList, selfVar);
				    MultivariateQuadFunction func = new MultivariateQuadFunction(arr, idStr, String.valueOf(arr[6]), globalInterval);
				    
				    // Adding the new neighbor to neighborStrList 
            String neighbor = String.valueOf(arr[6]);
            if (!neighborStrList.contains(neighbor)) neighborStrList.add(neighbor);

            PiecewiseMultivariateQuadFunction pwFunc = new PiecewiseMultivariateQuadFunction();
            pwFunc.addToFunctionList(func);
            functionList.add(pwFunc);

				    System.out.println("Agent " + idStr + " function " + pwFunc);
				}
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}
	
  //function -281x_2^2 199x_2 -22x_0^2 252x_0 288x_2x_0 358;
	// where x_2 is selfAgent and x_0 is the other agent
	/**
	 * @param line from input file
	 * @param selfAgent selfAgent name
	 * @return an array of coefficients. Make sure that the function reads its coefficients first
	 */
	public int[] parseFunction(String[] line, String selfAgent) {
    int coeffArray[] = new int [7];
    for (String str : line) {
      //x_1^2 or x_0x_2  
      if (str.contains(selfAgent)) {
          str = str.replace(selfAgent, "");
          
          // -281x_2^2 => -281^2
          if (str.contains("^2")) {
            coeffArray[0] = Integer.parseInt(str.replace("^2", ""));
 	        }
          // 199v2 => 199
          else if (!str.contains("x_")) {
             coeffArray[1] = Integer.parseInt(str);
          }
          // 288x_2x_0 => 288x_0
          else if (str.contains("x_")) {
             coeffArray[4] = Integer.parseInt(str.split("x_")[0]);
             // neighbor
             coeffArray[6] = Integer.parseInt(str.split("x_")[1]);
          }
      }
      else {
        // -22x_0^2
        if (str.contains("^2")) {   
          coeffArray[2] = Integer.parseInt(str.split("x_")[0]);
        }
        // 252x_0
        else if (str.contains("x_")) {
          coeffArray[3] = Integer.parseInt(str.split("x_")[0]);
        }
        // 358
        else {
          coeffArray[5] = Integer.parseInt(str);
        }
      }
    }
    return coeffArray;
	}
	
	public void registerWithDF () {
		DFAgentDescription dfd = new DFAgentDescription();
		dfd.setName(getAID());
		for (int i=0; i<neighborStrList.size(); i++) {
			ServiceDescription sd = new ServiceDescription();
			sd.setType(neighborStrList.get(i));
			sd.setName(idStr);
			dfd.addServices(sd);
		}
		try {
			DFService.register(this, dfd);
		}
		catch (FIPAException fe) {
			fe.printStackTrace();
		}
	}
	
	//get utility with parents, pseudoparents
	//then add its switching cost
//	public double utilityWithParentAndPseudoAndUnary() {
//		double sumUtility = 0;
//
////		for (int ts=0; ts<=h; ts++) {
//			List<Table> tableList = constraintTableAtEachTSMap.get(0);
//	    	for (Table constraintTable:tableList) {
//				List<String> decVarList = constraintTable.getDecVarLabel();
//				List<String> decValueList = new List<String>();
//				
//				//chi gui với constraint voi parent and pseudoparents
//				boolean notInParentList = false;
//				for (String var:decVarList) {
//					if (var.equals(idStr))
//						continue;
//					if (parentAndPseudoStrList.contains(var) == false) {
//						notInParentList = true;
//						break;
//					}
//				}
//				
//				if (notInParentList)
//					continue;
//				
//				for (String agentInList:decVarList) {
//					if (agentInList.equals(idStr))
//						decValueList.add(valueAtEachTSMap.get(0));
//					else
//						decValueList.add(agentView_DPOP_TSMap.get(agentInList).get(0));
//				}
//				sumUtility += constraintTable.getUtilityGivenDecValueList(decValueList);
//			}
////		}
//		return sumUtility;
//	}
	
//	public double calculcatingSwitchingCost() {
//		double sC = 0;
//		if (getValueAtEachTSMap().size() == 1) return 0;
//		for (int i=1; i<getValueAtEachTSMap().size(); i++) {
////			if (getValueAtEachTSMap().get(i).equals(getValueAtEachTSMap().get(i-1)) == false)
////				sC += switchingCost;
//			
//			sC += sc_func(getValueAtEachTSMap().get(i), getValueAtEachTSMap().get(i-1));
//		}
//		return sC;
//	}

	public String getIdStr() {
		return idStr;
	}

	public void setIdStr(String idStr) {
		this.idStr = idStr;
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

	public List<AID> getNeighborAIDList() {
		return neighborAIDList;
	}

	public void setNeighborAIDList(List<AID> neighborAIDList) {
		this.neighborAIDList = neighborAIDList;
	}

	public List<String> getNeighborStrList() {
		return neighborStrList;
	}

	public void setNeighborStrList(List<String> neighborStrList) {
		this.neighborStrList = neighborStrList;
	}

	public HashMap<AID, Integer> getConstraintInfoMap() {
		return constraintInfoMap;
	}

	public void setConstraintInfoMap(HashMap<AID, Integer> constraintInfoMap) {
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

	public void setCurrentStartTime(long currentStartTime) {
		this.currentStartTime = currentStartTime;
	}

	public HashMap<String, List<String>> getDecisionVariableDomainMap() {
		return decisionVariableDomainMap;
	}

	public void setDecisionVariableDomainMap(
			HashMap<String, List<String>> decisionVariableDomainMap) {
		this.decisionVariableDomainMap = decisionVariableDomainMap;
	}

	public HashMap<Integer, Double> getValueAtEachTSMap() {
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

	public List<Table> getCurrentTableListDPOP() {
		return currentTableListDPOP;
	}

	public void setCurrentTableListDPOP(List<Table> currentTableListDPOP) {
		this.currentTableListDPOP = currentTableListDPOP;
	}

	public HashMap<Integer, List<Table>> getConstraintTableAtEachTSMap() {
		return constraintTableAtEachTSMap;
	}

	public void setConstraintTableAtEachTSMap(
			HashMap<Integer, List<Table>> constraintTableAtEachTSMap) {
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

	public double getChosenValue() {
		return chosenValue;
	}

	public void setChosenValue(double chosenValue) {
		this.chosenValue = chosenValue;
	}

	public List<Double> getCurrentGlobalUtilityList() {
		return currentGlobalUtilityList;
	}

	public void setCurrentGlobalUtilityList(
			List<Double> currentGlobalUtilityList) {
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

	public static long getDelayMessageTime() {
		return delayMessageTime;
	}

	public static void setDelayMessageTime(long delayMessageTime) {
		DCOP.delayMessageTime = delayMessageTime;
	}

	public double getUtilFromChildrenLS() {
		return utilFromChildrenLS;
	}

	public void setUtilFromChildrenLS(double utilFromChildrenLS) {
		this.utilFromChildrenLS = utilFromChildrenLS;
	}
	
	public void addtoUtilFromChildrenLS(double util) {
		this.utilFromChildrenLS += util;
	}

	public Map<String, List<Double>> getAgentView_DPOP_TSMap() {
		return agentView_DPOP_TSMap;
	}

	public void setAgentView_DPOP_TSMap(
			Map<String, List<Double>> agentView_DPOP_TSMap) {
		this.agentView_DPOP_TSMap = agentView_DPOP_TSMap;
	}

	public long getEndTime() {
		return endTime;
	}

	public void setEndTime(long endTime) {
		this.endTime = endTime;
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

	public double getOldLSUtility() {
		return oldLSUtility;
	}

	public void setOldLSUtility(double oldLSUtility) {
		this.oldLSUtility = oldLSUtility;
	}
	
	public boolean isStop() {
		return stop;
	}

	public void setStop(boolean stop) {
		this.stop = stop;
	}
	
	public double getOldLSRunningTime() {
		return oldLSRunningTime;
	}

	public void setOldLSRunningTime(double oldLSRunningTime) {
		this.oldLSRunningTime = oldLSRunningTime;
	}
	
	public double getUtilityAndCost() {
		return utilityAndCost;
	}

	public void setUtilityAndCost(double utilityAndCost) {
		this.utilityAndCost = utilityAndCost;
	}
	
	public HashMap<String, Double> getValuesToSendInVALUEPhase() {
		return valuesToSendInVALUEPhase;
	}

	public void setValuesToSendInVALUEPhase(
			HashMap<String, Double> valuesToSendInVALUEPhase) {
		this.valuesToSendInVALUEPhase = valuesToSendInVALUEPhase;
	}
	
	public void addValuesToSendInValuePhase(String agent, Double value) {
		this.valuesToSendInVALUEPhase.put(agent, value);
	}
	
	public HashMap<Integer, String> getPickedRandomMap() {
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
	
    public long getCurrentUTILstartTime() {
		return currentUTILstartTime;
	}

	public void setCurrentUTILstartTime(long currentUTILstartTime) {
		this.currentUTILstartTime = currentUTILstartTime;
	}
	
	public String getLastLine() {
		return lastLine;
	}

	public void setLastLine(String lastLine) {
		this.lastLine = lastLine;
	}

  public List<PiecewiseMultivariateQuadFunction> getFunctionList() {
    return functionList;
  }

  public void setFunctionList(List<PiecewiseMultivariateQuadFunction> functionList) {
    this.functionList = functionList;
  }

  public PiecewiseMultivariateQuadFunction getAgentViewFunction() {
    return agentViewFunction;
  }

  public void setAgentViewFunction(PiecewiseMultivariateQuadFunction agentViewFunction) {
    this.agentViewFunction = agentViewFunction;
  }

  public List<PiecewiseMultivariateQuadFunction> getCurrentFunctionListDPOP() {
    return currentFunctionListDPOP;
  }

  public void setCurrentFunctionListDPOP(List<PiecewiseMultivariateQuadFunction> currentFunctionListDPOP) {
    this.currentFunctionListDPOP = currentFunctionListDPOP;
  }

  public int getNumberOfIntervals() {
    return numberOfIntervals;
  }

  public void setNumberOfIntervals(int numberOfIntervals) {
    this.numberOfIntervals = numberOfIntervals;
  }
}	