package com.common;
/*******************************************************************************
 * Copyright (C) 2014  Stefan Schroeder
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/


import jsprit.analysis.toolbox.GraphStreamViewer;
import jsprit.analysis.toolbox.GraphStreamViewer.Label;

import jsprit.core.algorithm.VehicleRoutingAlgorithm;
import jsprit.core.algorithm.VehicleRoutingAlgorithmBuilder;
import jsprit.core.algorithm.state.StateId;
import jsprit.core.algorithm.state.StateManager;
import jsprit.core.algorithm.state.StateUpdater;
import jsprit.core.problem.Location;
import jsprit.core.problem.VehicleRoutingProblem;
import jsprit.core.problem.VehicleRoutingProblem.Builder;
import jsprit.core.problem.VehicleRoutingProblem.FleetSize;
import jsprit.core.problem.constraint.ConstraintManager;
import jsprit.core.problem.constraint.HardActivityConstraint;
import jsprit.core.problem.constraint.HardRouteConstraint;
import jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import jsprit.core.problem.driver.DriverImpl;
import jsprit.core.problem.io.VrpXMLWriter;
import jsprit.core.problem.job.Job;
import jsprit.core.problem.job.Shipment;
import jsprit.core.problem.misc.JobInsertionContext;
import jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import jsprit.core.problem.solution.route.VehicleRoute;
import jsprit.core.problem.solution.route.activity.ReverseActivityVisitor;
import jsprit.core.problem.solution.route.activity.TourActivity;
import jsprit.core.problem.solution.route.activity.TourActivity.JobActivity;
import jsprit.core.problem.solution.route.state.RouteAndActivityStateGetter;
import jsprit.core.problem.vehicle.*;
import jsprit.core.reporting.SolutionPrinter;
import jsprit.core.util.Coordinate;
import jsprit.core.util.CrowFlyCosts;
import jsprit.core.util.Solutions;
import jsprit.util.Examples;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.print.DocFlavor.INPUT_STREAM;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.logging.log4j.LogManager;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.w3c.dom.CharacterData;


/**
 * This class provides the/a solution to the following problem:
 * 
 * Statement of the problem (see Stackoverflow: http://stackoverflow.com/questions/19080537/bicycle-messenger-tsppd-with-optaplanner/20412598#20412598):
 * 
 * Optimize the routes for a bicycle messenger service! 
 * Assume 5 messengers that have to pick up 30 envelopes distributed through the city. These 5 messengers are distributed through the city as well. Thus
 * there is no single depot and they do not need to go back to their original starting location.
 * 
 * Additional hard constraints:
 * 1) Every messenger can carry up to fifteen envelopes
 * 2) The way an evelopes travels should be less than three times the direct route (so delivery does not take too long)
 * 
 * Thus this problem is basically a Capacitated VRP with Pickups and Deliveries, Multiple Depots, Open Routes and Time Windows/Restrictions.
 * 
 * @author stefan schroeder
 *
 */
public class BicycleMessenger1 {

	/**
	 * Hard constraint: delivery of envelope must not take longer than 3*bestDirect (i.e. fastest messenger on direct delivery) 
	 * 
	 * @author stefan
	 *
	
	 */
	public static String input_dir;
	public static String output_dir;
	public static String config_dir;
	public static String messengerXML;
	public static String envelopXML;
	public BicycleMessenger1()
	{
		
	}
	
	public BicycleMessenger1(String input_dir, String output_dir, String config_dir, String messengerXML, String envelopXML)
	{
		this.input_dir = input_dir;
		this.output_dir = output_dir;
		this.config_dir = config_dir;
		this.messengerXML = messengerXML;
		this.envelopXML = envelopXML;
	}
	static class ThreeTimesLessThanBestDirectRouteConstraint implements HardActivityConstraint {

		private final VehicleRoutingTransportCosts routingCosts;
		
		private final RouteAndActivityStateGetter stateManager;
		
		//jobId map direct-distance by nearestMessenger
		private final Map<String,Double> bestMessengers;

        private final StateId latest_act_arrival_time_stateId;
		
		public ThreeTimesLessThanBestDirectRouteConstraint(StateId latest_act_arrival_time, Map<String, Double> nearestMessengers, VehicleRoutingTransportCosts routingCosts, RouteAndActivityStateGetter stateManager) {
			this.bestMessengers = nearestMessengers;
			this.routingCosts = routingCosts;
			this.stateManager = stateManager;
            this.latest_act_arrival_time_stateId = latest_act_arrival_time;
		}

		@Override
		public ConstraintsStatus fulfilled(JobInsertionContext iFacts,TourActivity prevAct, TourActivity newAct, TourActivity nextAct, double prevActDepTime) {
			//make sure vehicle can manage direct path
			double arrTime_at_nextAct_onDirectRoute = prevActDepTime + routingCosts.getTransportTime(prevAct.getLocation(), nextAct.getLocation(), prevActDepTime, iFacts.getNewDriver(), iFacts.getNewVehicle());
            Double latest_arrTime_at_nextAct = stateManager.getActivityState(nextAct, latest_act_arrival_time_stateId, Double.class);
            if(latest_arrTime_at_nextAct == null) latest_arrTime_at_nextAct = nextAct.getTheoreticalLatestOperationStartTime();
            if(arrTime_at_nextAct_onDirectRoute > latest_arrTime_at_nextAct){
                //constraint can never be fulfilled anymore, thus .NOT_FULFILLED_BREAK
				return ConstraintsStatus.NOT_FULFILLED_BREAK;
			}
			
			double arrTime_at_newAct = prevActDepTime + routingCosts.getTransportTime(prevAct.getLocation(), newAct.getLocation(), prevActDepTime, iFacts.getNewDriver(), iFacts.getNewVehicle());
			//local impact
			//no matter whether it is a pickupShipment or deliverShipment activities. both arrivalTimes must be < 3*best. 
			double directTimeOfNearestMessenger = bestMessengers.get(((JobActivity) newAct).getJob().getId());
			if(arrTime_at_newAct > 3 * directTimeOfNearestMessenger){
				//not fulfilled AND it can never be fulfilled anymore by going forward in route, thus NOT_FULFILLED_BREAK
				return ConstraintsStatus.NOT_FULFILLED_BREAK;
			}

			//impact on whole route, since insertion of newAct shifts all subsequent activities forward in time
			double departureTime_at_newAct = arrTime_at_newAct + newAct.getOperationTime();
            double latest_arrTime_at_newAct = latest_arrTime_at_nextAct - routingCosts.getTransportTime(newAct.getLocation(),nextAct.getLocation(),departureTime_at_newAct,iFacts.getNewDriver(),iFacts.getNewVehicle());
			if(arrTime_at_newAct > latest_arrTime_at_newAct){
                return ConstraintsStatus.NOT_FULFILLED;
            }

            double arrTime_at_nextAct = departureTime_at_newAct + routingCosts.getTransportTime(newAct.getLocation(), nextAct.getLocation(), departureTime_at_newAct, iFacts.getNewDriver(), iFacts.getNewVehicle());
			//here you need an activity state
			if(arrTime_at_nextAct > latest_arrTime_at_nextAct){
				return ConstraintsStatus.NOT_FULFILLED;
			}
			return ConstraintsStatus.FULFILLED;
		}
		
	}
	
	/**
	 * When inserting the activities of an envelope which are pickup and deliver envelope, this constraint makes insertion procedure to ignore messengers that are too far away to meet the 3*directTime-Constraint.  
	 * 
	 * <p>one does not need this constraint. but it is faster. the earlier the solution-space can be constraint the better/faster.
	 * @author schroeder
	 *
	 */
	static class IgnoreMessengerThatCanNeverMeetTimeRequirements implements HardRouteConstraint {

		private final Map<String,Double> bestMessengers;
		
		private final VehicleRoutingTransportCosts routingCosts;
		
		public IgnoreMessengerThatCanNeverMeetTimeRequirements(Map<String, Double> bestMessengers, VehicleRoutingTransportCosts routingCosts) {
			super();
			this.bestMessengers = bestMessengers;
			this.routingCosts = routingCosts;
		}

		@Override
		public boolean fulfilled(JobInsertionContext insertionContext) {
			double timeOfDirectRoute = getTimeOfDirectRoute(insertionContext.getJob(), insertionContext.getNewVehicle(), routingCosts); 
			double timeOfNearestMessenger = bestMessengers.get(insertionContext.getJob().getId());
			return !(timeOfDirectRoute > 3 * timeOfNearestMessenger);
		}
		
	}
	
	/**
	 * updates the state "latest-activity-start-time" (required above) once route/activity states changed, i.e. when removing or inserting an envelope-activity
	 * 
	 * <p>thus once either the insertion-procedure starts or an envelope has been inserted, this visitor runs through the route in reverse order (i.e. starting with the end of the route) and 
	 * calculates the latest-activity-start-time (or latest-activity-arrival-time) which is the time to just meet the constraints of subsequent activities.
	 *   
	 * @author schroeder
	 *
	 */
	static class UpdateLatestActivityStartTimes implements StateUpdater, ReverseActivityVisitor {

		private final StateManager stateManager;
		
		private final VehicleRoutingTransportCosts routingCosts;
		
		private final Map<String,Double> bestMessengers;
		
		private VehicleRoute route;
		
		private TourActivity prevAct;
		
		private double latest_arrTime_at_prevAct;

        private final StateId latest_act_arrival_time_stateId;
		
		public UpdateLatestActivityStartTimes(StateId latest_act_arrival_time, StateManager stateManager, VehicleRoutingTransportCosts routingCosts, Map<String, Double> bestMessengers) {
			super();
			this.stateManager = stateManager;
			this.routingCosts = routingCosts;
			this.bestMessengers = bestMessengers;
            this.latest_act_arrival_time_stateId = latest_act_arrival_time;
		}

		@Override
		public void begin(VehicleRoute route) {
			this.route = route;
			latest_arrTime_at_prevAct = route.getEnd().getTheoreticalLatestOperationStartTime();
			prevAct = route.getEnd();
		}

		@Override
		public void visit(TourActivity currAct) {
			double timeOfNearestMessenger = bestMessengers.get(((JobActivity)currAct).getJob().getId());
			double potential_latest_arrTime_at_currAct =
					latest_arrTime_at_prevAct - routingCosts.getBackwardTransportTime(currAct.getLocation(), prevAct.getLocation(), latest_arrTime_at_prevAct, route.getDriver(),route.getVehicle()) - currAct.getOperationTime();
			double latest_arrTime_at_currAct = Math.min(3*timeOfNearestMessenger, potential_latest_arrTime_at_currAct);
			stateManager.putActivityState(currAct, latest_act_arrival_time_stateId, latest_arrTime_at_currAct);
			assert currAct.getArrTime() <= latest_arrTime_at_currAct : "this must not be since it breaks condition; actArrTime: " + currAct.getArrTime() + " latestArrTime: " + latest_arrTime_at_currAct + " vehicle: " + route.getVehicle().getId();
			latest_arrTime_at_prevAct = latest_arrTime_at_currAct;
			prevAct = currAct;
		}

		@Override
		public void finish() {}
		
	}
	
	public void generatePaths() throws Exception
	{
		LogManager.getLogger(BicycleMessenger1.class);
		Examples.createOutputFolder(output_dir);
		System.out.println("Output folder created");
		
		/*
		build the problem
		 */
		VehicleRoutingProblem.Builder problemBuilder = VehicleRoutingProblem.Builder.newInstance();
		problemBuilder.setFleetSize(FleetSize.FINITE);
		readEnvelopes(problemBuilder, envelopXML);
		readMessengers(problemBuilder, messengerXML);
		System.out.println("Inside ReadMessenger");
		//add constraints to problem
		VehicleRoutingTransportCosts routingCosts = new CrowFlyCosts(problemBuilder.getLocations()); //which is the default VehicleRoutingTransportCosts in builder above
        problemBuilder.setRoutingCost(routingCosts);
        //finally build the problem
//        problemBuilder.addPenaltyVehicles(20.0,50000);
        VehicleRoutingProblem bicycleMessengerProblem = problemBuilder.build();
        System.out.println("Build");
        /*
        define states and constraints
         */
        //map mapping nearest messengers, i.e. for each envelope the direct-delivery-time with the fastest messenger is stored here
        Map<String,Double> nearestMessengers = getNearestMessengers(routingCosts, problemBuilder.getAddedJobs(), problemBuilder.getAddedVehicles());

        //define stateManager to update the required activity-state: "latest-activity-start-time"
        StateManager stateManager = new StateManager(bicycleMessengerProblem);
        //create state
        StateId latest_act_arrival_time_stateId = stateManager.createStateId("latest-act-arrival-time");
        //and make sure you update the activity-state "latest-activity-start-time" the way it is defined above
        stateManager.addStateUpdater(new UpdateLatestActivityStartTimes(latest_act_arrival_time_stateId, stateManager, routingCosts, nearestMessengers));
        stateManager.updateLoadStates();

        ConstraintManager constraintManager = new ConstraintManager(bicycleMessengerProblem,stateManager);
        constraintManager.addLoadConstraint();
        constraintManager.addConstraint(new ThreeTimesLessThanBestDirectRouteConstraint(latest_act_arrival_time_stateId,nearestMessengers,routingCosts,stateManager), ConstraintManager.Priority.CRITICAL);
        constraintManager.addConstraint(new IgnoreMessengerThatCanNeverMeetTimeRequirements(nearestMessengers, routingCosts));

        //create your algorithm
        //VehicleRoutingAlgorithmBuilder vraBuilder = new VehicleRoutingAlgorithmBuilder(bicycleMessengerProblem,"E:\\Invero\\apache-tomcat-6.0.43\\webapps\\PineappleEasyRealState\\input\\algorithmConfig.xml");
        VehicleRoutingAlgorithmBuilder vraBuilder = new VehicleRoutingAlgorithmBuilder(bicycleMessengerProblem,config_dir + "/algorithmConfig.xml");

        //        vraBuilder.setNuOfThreads(2);
        vraBuilder.addDefaultCostCalculators();
        vraBuilder.setStateAndConstraintManager(stateManager, constraintManager);
		vraBuilder.setNuOfThreads(10);
		System.out.println("Start1");
        VehicleRoutingAlgorithm algorithm = vraBuilder.build();
        algorithm.setMaxIterations(10000);
//        VariationCoefficientTermination prematureAlgorithmTermination = new VariationCoefficientTermination(200, 0.001);
//        algorithm.setPrematureAlgorithmTermination(prematureAlgorithmTermination);
//        algorithm.addListener(prematureAlgorithmTermination);
//		algorithm.addListener(new AlgorithmSearchProgressChartListener("output/progress.png"));

        //search
        System.out.println("Start");
		Collection<VehicleRoutingProblemSolution> solutions = algorithm.searchSolutions();
		System.out.println("End");
		//this is just to ensure that solution meet the above constraints
		validateSolution(Solutions.bestOf(solutions), bicycleMessengerProblem, nearestMessengers);

        SolutionPrinter.print(bicycleMessengerProblem, Solutions.bestOf(solutions), SolutionPrinter.Print.VERBOSE, output_dir);
        new VrpXMLWriter(bicycleMessengerProblem, solutions).write(output_dir + "/bicycleMessenger.xml");

		//you may want to plot the problem
        /*
		Plotter plotter = new Plotter(bicycleMessengerProblem);
//		plotter.setBoundingBox(10000, 47500, 20000, 67500);
		plotter.plotShipments(true);
		plotter.plot("output/bicycleMessengerProblem.png", "bicycleMessenger");
		
		//and the problem as well as the solution
		Plotter plotter1 = new Plotter(bicycleMessengerProblem, Solutions.bestOf(solutions));
		plotter1.setLabel(Plotter.Label.ID);
        plotter1.plotShipments(false);
//		plotter1.setBoundingBox(5000, 45500, 25000, 66500);
		plotter1.plot("output/bicycleMessengerSolution.png", "bicycleMessenger");
		
		//and write out your solution in xml
//		new VrpXMLWriter(bicycleMessengerProblem, solutions).write("output/bicycleMessenger.xml");
		

		
		new GraphStreamViewer(bicycleMessengerProblem).labelWith(Label.ID).setRenderShipments(true).setRenderDelay(150).display();
//		
		new GraphStreamViewer(bicycleMessengerProblem, Solutions.bestOf(solutions)).setGraphStreamFrameScalingFactor(1.5).setCameraView(12500, 55000, 0.25).labelWith(Label.ACTIVITY).setRenderShipments(true).setRenderDelay(150).display();
		*/

	}
	
	/**
	 *
	 * @throws IOException 
	 */
	public static void main(String[] args) throws IOException {
		//org.apache.logging.log4j.PropertyConfigurator.configure("E:\\Invero\\Projects\\VRP\\VRP\\src\\main\\resources\\log4j.properties");
		LogManager.getLogger(BicycleMessenger1.class);
		Examples.createOutputFolder(output_dir);
		
		/*
		build the problem
		 */
		VehicleRoutingProblem.Builder problemBuilder = VehicleRoutingProblem.Builder.newInstance();
		problemBuilder.setFleetSize(FleetSize.FINITE);
		readEnvelopes(problemBuilder, envelopXML);
	
		readMessengers(problemBuilder, messengerXML);
		//add constraints to problem
		VehicleRoutingTransportCosts routingCosts = new CrowFlyCosts(problemBuilder.getLocations()); //which is the default VehicleRoutingTransportCosts in builder above
		System.out.println("Start Building");
        problemBuilder.setRoutingCost(routingCosts);
        //finally build the problem
//        problemBuilder.addPenaltyVehicles(20.0,50000);
        System.out.println("Start Building");
        VehicleRoutingProblem bicycleMessengerProblem = problemBuilder.build();

        /*
        define states and constraints
         */
        //map mapping nearest messengers, i.e. for each envelope the direct-delivery-time with the fastest messenger is stored here
        Map<String,Double> nearestMessengers = getNearestMessengers(routingCosts, problemBuilder.getAddedJobs(), problemBuilder.getAddedVehicles());

        //define stateManager to update the required activity-state: "latest-activity-start-time"
        StateManager stateManager = new StateManager(bicycleMessengerProblem);
        //create state
        StateId latest_act_arrival_time_stateId = stateManager.createStateId("latest-act-arrival-time");
        //and make sure you update the activity-state "latest-activity-start-time" the way it is defined above
        stateManager.addStateUpdater(new UpdateLatestActivityStartTimes(latest_act_arrival_time_stateId, stateManager, routingCosts, nearestMessengers));
        stateManager.updateLoadStates();

        ConstraintManager constraintManager = new ConstraintManager(bicycleMessengerProblem,stateManager);
        constraintManager.addLoadConstraint();
        constraintManager.addConstraint(new ThreeTimesLessThanBestDirectRouteConstraint(latest_act_arrival_time_stateId,nearestMessengers,routingCosts,stateManager), ConstraintManager.Priority.CRITICAL);
        constraintManager.addConstraint(new IgnoreMessengerThatCanNeverMeetTimeRequirements(nearestMessengers, routingCosts));

        //create your algorithm
        VehicleRoutingAlgorithmBuilder vraBuilder = new VehicleRoutingAlgorithmBuilder(bicycleMessengerProblem,config_dir + "/algorithmConfig.xml");
//        vraBuilder.setNuOfThreads(2);
        vraBuilder.addDefaultCostCalculators();
        vraBuilder.setStateAndConstraintManager(stateManager, constraintManager);
		vraBuilder.setNuOfThreads(10);
		
        VehicleRoutingAlgorithm algorithm = vraBuilder.build();
        algorithm.setMaxIterations(10000);
        System.out.println("Start Building");
//        VariationCoefficientTermination prematureAlgorithmTermination = new VariationCoefficientTermination(200, 0.001);
//        algorithm.setPrematureAlgorithmTermination(prematureAlgorithmTermination);
//        algorithm.addListener(prematureAlgorithmTermination);
//		algorithm.addListener(new AlgorithmSearchProgressChartListener("output/progress.png"));

        //search
		Collection<VehicleRoutingProblemSolution> solutions = algorithm.searchSolutions();
		System.out.println("Found Solutions");
		//this is just to ensure that solution meet the above constraints
		validateSolution(Solutions.bestOf(solutions), bicycleMessengerProblem, nearestMessengers);

        SolutionPrinter.print(bicycleMessengerProblem, Solutions.bestOf(solutions), SolutionPrinter.Print.VERBOSE, output_dir);
        new VrpXMLWriter(bicycleMessengerProblem, solutions).write(output_dir + "/bicycleMessenger.xml");

		//you may want to plot the problem
        /*
		Plotter plotter = new Plotter(bicycleMessengerProblem);
//		plotter.setBoundingBox(10000, 47500, 20000, 67500);
		plotter.plotShipments(true);
		plotter.plot("output/bicycleMessengerProblem.png", "bicycleMessenger");
		
		//and the problem as well as the solution
		Plotter plotter1 = new Plotter(bicycleMessengerProblem, Solutions.bestOf(solutions));
		plotter1.setLabel(Plotter.Label.ID);
        plotter1.plotShipments(false);
//		plotter1.setBoundingBox(5000, 45500, 25000, 66500);
		plotter1.plot("output/bicycleMessengerSolution.png", "bicycleMessenger");
		
		//and write out your solution in xml
//		new VrpXMLWriter(bicycleMessengerProblem, solutions).write("output/bicycleMessenger.xml");
		

		
		new GraphStreamViewer(bicycleMessengerProblem).labelWith(Label.ID).setRenderShipments(true).setRenderDelay(150).display();
//		
		new GraphStreamViewer(bicycleMessengerProblem, Solutions.bestOf(solutions)).setGraphStreamFrameScalingFactor(1.5).setCameraView(12500, 55000, 0.25).labelWith(Label.ACTIVITY).setRenderShipments(true).setRenderDelay(150).display();
		*/
	}

	//if you wanne run this enable assertion by putting an '-ea' in your vmargument list - Run As --> Run Configurations --> (x)=Arguments --> VM arguments: -ea
	private static void validateSolution(VehicleRoutingProblemSolution bestOf, VehicleRoutingProblem bicycleMessengerProblem, Map<String, Double> nearestMessengers) {
		for(VehicleRoute route : bestOf.getRoutes()){
			for(TourActivity act : route.getActivities()){ 
				if(act.getArrTime() > 3*nearestMessengers.get(((JobActivity)act).getJob().getId())){
					SolutionPrinter.print(bicycleMessengerProblem, bestOf, SolutionPrinter.Print.VERBOSE, output_dir);
					throw new IllegalStateException("three times less than ... constraint broken. this must not be. act.getArrTime(): " + act.getArrTime() + " allowed: " + 3*nearestMessengers.get(((JobActivity)act).getJob().getId())); 
				}
			}
			if(route.getVehicle().getType() instanceof PenaltyVehicleType){
				SolutionPrinter.print(bicycleMessengerProblem, bestOf, SolutionPrinter.Print.VERBOSE, output_dir);
				throw new IllegalStateException("penaltyVehicle in solution. if there is a valid solution, this should not be");
			}
		}
	}

	static Map<String,Double> getNearestMessengers(VehicleRoutingTransportCosts routingCosts, Collection<Job> envelopes, Collection<Vehicle> messengers) {
		Map<String,Double> nearestMessengers = new HashMap<String, Double>();	
		for(Job envelope : envelopes){
			double minDirect = Double.MAX_VALUE;
			for(Vehicle m : messengers){
				double direct = getTimeOfDirectRoute(envelope, m, routingCosts);
				if(direct < minDirect){
					minDirect = direct;
				}
			}
			nearestMessengers.put(envelope.getId(), minDirect);
		}
		return nearestMessengers;
	}
	
	static double getTimeOfDirectRoute(Job job, Vehicle v, VehicleRoutingTransportCosts routingCosts) {
		Shipment envelope = (Shipment) job;
		return routingCosts.getTransportTime(v.getStartLocation(), envelope.getPickupLocation(), 0.0, DriverImpl.noDriver(), v) +
				routingCosts.getTransportTime(envelope.getPickupLocation(), envelope.getDeliveryLocation(), 0.0, DriverImpl.noDriver(), v);
	}
	
	private static void readEnvelopes(Builder problemBuilder, String messengerXML)
	{
		try{
		DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
	    InputSource is = new InputSource();
	    //is.setCharacterStream(new StringReader(messengerXML));
	    org.w3c.dom.Document doc = db.parse(new InputSource(new StringReader(messengerXML)));
	    NodeList nodes = doc.getElementsByTagName("item");
	    
	    for (int i = 0; i < nodes.getLength(); i++) {
	        Element element = (Element) nodes.item(i);

	        NodeList name = element.getElementsByTagName("name");
	        Element line = (Element) name.item(0);
	        String itemName = getCharacterDataFromElement(line);
	        System.out.println("Name: " + getCharacterDataFromElement(line));
	        
	        NodeList id = element.getElementsByTagName("id");
	        line = (Element) id.item(0);
	        String itemId = getCharacterDataFromElement(line);
	        System.out.println("Name: " + getCharacterDataFromElement(line));
	        
	        NodeList pickup_x = element.getElementsByTagName("pickup_x");
	        line = (Element) pickup_x.item(0);
	        String itemPickUpX = getCharacterDataFromElement(line);
	        System.out.println("Name: " + getCharacterDataFromElement(line));
	        
	        NodeList pickup_y = element.getElementsByTagName("pickup_y");
	        line = (Element) pickup_y.item(0);
	        String itemPickUpY = getCharacterDataFromElement(line);
	        System.out.println("Name: " + getCharacterDataFromElement(line));
	        
	        NodeList deliver_x = element.getElementsByTagName("deliver_x");
	        line = (Element) deliver_x.item(0);
	        String itemDeliverX = getCharacterDataFromElement(line);
	        System.out.println("Name: " + getCharacterDataFromElement(line));

	        NodeList deliver_y = element.getElementsByTagName("deliver_y");
	        line = (Element) deliver_y.item(0);
	        String itemDeliverY = getCharacterDataFromElement(line);
	        System.out.println("Title: " + getCharacterDataFromElement(line));
	        
	        Shipment envelope = Shipment.Builder.newInstance(itemId).addSizeDimension(0, 1)
			.setPickupLocation(Location.Builder.newInstance().setCoordinate(Coordinate.newInstance(Double.parseDouble(itemPickUpX), Double.parseDouble(itemPickUpY))).build())
			.setDeliveryLocation(Location.Builder.newInstance().setCoordinate(Coordinate.newInstance(Double.parseDouble(itemDeliverX), Double.parseDouble(itemDeliverY))).build()).build();
	        problemBuilder.addJob(envelope);
	    }
		}
		catch(Exception e)
		{
			
		}
	}
	
	
	public static String getCharacterDataFromElement(Element e) {
	    Node child = e.getFirstChild();
	    if (child instanceof CharacterData) {
	      CharacterData cd = (CharacterData) child;
	      return cd.getData();
	    }
	    return "";
	  }
	
	private static void readMessengers(Builder problemBuilder, String messengerXML) 
	{
		try{
		DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
	    //InputSource is = new InputSource();
	    //is.setCharacterStream(new StringReader(messengerXML));
		VehicleType messengerType = VehicleTypeImpl.Builder.newInstance("messengerType").addCapacityDimension(0, 15).setCostPerDistance(1).build();
	    org.w3c.dom.Document doc = db.parse(new InputSource(new StringReader(messengerXML)));
	    NodeList nodes = doc.getElementsByTagName("item");
	    
	    for (int i = 0; i < nodes.getLength(); i++) {
	        Element element = (Element) nodes.item(i);

	        NodeList name = element.getElementsByTagName("type");
	        Element line = (Element) name.item(0);
	        String itemType = getCharacterDataFromElement(line);
	        System.out.println("Name: " + getCharacterDataFromElement(line));
	        
	        NodeList id = element.getElementsByTagName("id");
	        line = (Element) id.item(0);
	        String itemId = getCharacterDataFromElement(line);
	        System.out.println("Name: " + getCharacterDataFromElement(line));
	        
	        NodeList pickup_x = element.getElementsByTagName("x");
	        line = (Element) pickup_x.item(0);
	        String itemPickUpX = getCharacterDataFromElement(line);
	        System.out.println("Name: " + getCharacterDataFromElement(line));
	        
	        NodeList pickup_y = element.getElementsByTagName("y");
	        line = (Element) pickup_y.item(0);
	        String itemPickUpY = getCharacterDataFromElement(line);
	        System.out.println("Name: " + getCharacterDataFromElement(line));
	        
	        	        
	        VehicleImpl vehicle = VehicleImpl.Builder.newInstance(itemId)
			.setStartLocation(Location.Builder.newInstance().setCoordinate(Coordinate.newInstance(Double.parseDouble(itemPickUpX), Double.parseDouble(itemPickUpY))).build()).setReturnToDepot(false).setType(messengerType).build();
	        problemBuilder.addVehicle(vehicle);
	    }
		}catch(Exception e)
		{
			e.printStackTrace();
		}
	}


	private static void readEnvelopesOld(Builder problemBuilder) throws IOException {
		String path = "";
		BufferedReader reader = new BufferedReader(new FileReader(new File(input_dir + "/bicycle_messenger_demand.txt")));
		String line;
		boolean firstLine = true;
		while((line = reader.readLine()) != null){
			if(firstLine) { firstLine = false; continue; }
			String[] tokens = line.split("\\s+");
			System.out.println(line);
			//define your envelope which is basically a shipment from A to B
			Shipment envelope = Shipment.Builder.newInstance(tokens[1]).addSizeDimension(0, 1)
					.setPickupLocation(Location.Builder.newInstance().setCoordinate(Coordinate.newInstance(Double.parseDouble(tokens[2]), Double.parseDouble(tokens[3]))).build())
					.setDeliveryLocation(Location.Builder.newInstance().setCoordinate(Coordinate.newInstance(Double.parseDouble(tokens[4]), Double.parseDouble(tokens[5]))).build()).build();
			problemBuilder.addJob(envelope);
		}
		reader.close();
		
	}

	private static void readMessengersOld(Builder problemBuilder) throws IOException {
		BufferedReader reader = new BufferedReader(new FileReader(new File(input_dir + "/bicycle_messenger_supply.txt")));
		String line;
		boolean firstLine = true;
		VehicleType messengerType = VehicleTypeImpl.Builder.newInstance("messengerType").addCapacityDimension(0, 15).setCostPerDistance(1).build();
		/*
		 * the algo requires some time and space to search for a valid solution. if you ommit a penalty-type, it probably throws an Exception once it cannot insert an envelope anymore
		 * thus, give it space by defining a penalty/shadow vehicle with higher variable and fixed costs to up the pressure to find solutions without penalty type
		 * 
		 * it is important to give it the same typeId as the type you want to shadow
		 */
		while((line = reader.readLine()) != null){
			if(firstLine) { firstLine = false; continue; }
			String[] tokens = line.split("\\s+");
			System.out.print(line);
			//build your vehicle
			VehicleImpl vehicle = VehicleImpl.Builder.newInstance(tokens[1])
					.setStartLocation(Location.Builder.newInstance().setCoordinate(Coordinate.newInstance(Double.parseDouble(tokens[2]), Double.parseDouble(tokens[3]))).build())
					.setReturnToDepot(false).setType(messengerType).build();
			problemBuilder.addVehicle(vehicle);
		}
		reader.close();
	}

}
