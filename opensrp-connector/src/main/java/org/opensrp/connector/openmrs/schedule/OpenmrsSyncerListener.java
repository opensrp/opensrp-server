package org.opensrp.connector.openmrs.schedule;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.joda.time.DateTime;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.motechproject.scheduler.domain.MotechEvent;
import org.motechproject.server.event.annotations.MotechListener;
import org.opensrp.connector.dhis2.Dhis2TrackCaptureConnector;
import org.opensrp.connector.openmrs.constants.OpenmrsConstants;
import org.opensrp.connector.openmrs.constants.OpenmrsConstants.SchedulerConfig;
import org.opensrp.connector.openmrs.exception.HouseholdNotFoundException;
import org.opensrp.connector.openmrs.exception.IdentifierNotFoundException;
import org.opensrp.connector.openmrs.exception.RelationshipNotFoundException;
import org.opensrp.connector.openmrs.service.EncounterService;
import org.opensrp.connector.openmrs.service.PatientService;
import org.opensrp.domain.AppStateToken;
import org.opensrp.domain.Client;
import org.opensrp.domain.Event;
import org.opensrp.domain.Multimedia;
import org.opensrp.scheduler.service.ActionService;
import org.opensrp.scheduler.service.ScheduleService;
import org.opensrp.service.ClientService;
import org.opensrp.service.ConfigService;
import org.opensrp.service.ErrorTraceService;
import org.opensrp.service.EventService;
import org.opensrp.service.MultimediaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.management.relation.RelationNotFoundException;

@Component
public class OpenmrsSyncerListener {

	private static final ReentrantLock lock = new ReentrantLock();

	private static Logger logger = LoggerFactory.getLogger(OpenmrsSyncerListener.class.toString());

	//private final OpenmrsSchedulerService openmrsSchedulerService;

	private final ScheduleService opensrpScheduleService;

	private final ActionService actionService;

	private final ConfigService config;

	private final ErrorTraceService errorTraceService;

	private final PatientService patientService;

	private final EncounterService encounterService;

	private final EventService eventService;

	private final ClientService clientService;

	// private RelationShipService relationShipService;

	@Autowired
	private Dhis2TrackCaptureConnector dhis2TrackCaptureConnector;

	@Autowired
	MultimediaService multimediaService;

	@Autowired
	public OpenmrsSyncerListener(ScheduleService opensrpScheduleService, ActionService actionService, ConfigService config,
	                             ErrorTraceService errorTraceService, PatientService patientService, EncounterService encounterService,
	                             ClientService clientService, EventService eventService) {
		//this.openmrsSchedulerService = openmrsSchedulerService;
		this.opensrpScheduleService = opensrpScheduleService;
		this.actionService = actionService;
		this.config = config;
		this.errorTraceService = errorTraceService;
		this.patientService = patientService;
		this.encounterService = encounterService;
		this.eventService = eventService;
		this.clientService = clientService;

		this.config.registerAppStateToken(SchedulerConfig.openmrs_syncer_sync_schedule_tracker_by_last_update_enrollment, 0,
				"ScheduleTracker token to keep track of enrollment synced with OpenMRS", true);

		this.config.registerAppStateToken(SchedulerConfig.openmrs_syncer_sync_client_by_date_updated, 0,
				"OpenMRS data pusher token to keep track of new / updated clients synced with OpenMRS", true);

		this.config.registerAppStateToken(SchedulerConfig.openmrs_syncer_sync_client_by_date_voided, 0,
				"OpenMRS data pusher token to keep track of voided clients synced with OpenMRS", true);

		this.config.registerAppStateToken(SchedulerConfig.openmrs_syncer_sync_event_by_date_updated, 0,
				"OpenMRS data pusher token to keep track of new / updated events synced with OpenMRS", true);

		this.config.registerAppStateToken(SchedulerConfig.openmrs_syncer_sync_event_by_date_voided, 0,
				"OpenMRS data pusher token to keep track of voided events synced with OpenMRS", true);
	}

	// @MotechListener(subjects =
	// OpenmrsConstants.SCHEDULER_TRACKER_SYNCER_SUBJECT)
	// public void scheduletrackerSyncer(MotechEvent event) {
	// try {
	// logger.info("RUNNING " + event.getSubject());
	// AppStateToken lastsync =
	// config.getAppStateTokenByName(SchedulerConfig.openmrs_syncer_sync_schedule_tracker_by_last_update_enrollment);
	// DateTime start = lastsync == null || lastsync.getValue() == null ? new
	// DateTime().minusYears(33) : new DateTime(lastsync.stringValue());
	// DateTime end = new DateTime();
	// List<Enrollment> el =
	// opensrpScheduleService.findEnrollmentByLastUpDate(start, end);
	// for (Enrollment e : el) {
	// DateTime alertstart = e.getStartOfSchedule();
	// DateTime alertend = e.getLastFulfilledDate();
	// if (alertend == null) {
	// alertend = e.getCurrentMilestoneStartDate();
	// }
	// try {
	// if (e.getMetadata().get(OpenmrsConstants.ENROLLMENT_TRACK_UUID) != null)
	// {
	// openmrsSchedulerService.updateTrack(e,
	// actionService.findByCaseIdScheduleAndTimeStamp(e.getExternalId(),
	// e.getScheduleName(), alertstart, alertend));
	// } else {
	// JSONObject tr = openmrsSchedulerService.createTrack(e,
	// actionService.findByCaseIdScheduleAndTimeStamp(e.getExternalId(),
	// e.getScheduleName(), alertstart, alertend));
	// opensrpScheduleService.updateEnrollmentWithMetadata(e.getId(),
	// OpenmrsConstants.ENROLLMENT_TRACK_UUID, tr.getString("uuid"));
	// }
	// } catch (Exception e1) {
	// e1.printStackTrace();
	// errorTraceService.log("ScheduleTracker Syncer Inactive Schedule",
	// Enrollment.class.getName(), e.getId(), e1.getStackTrace().toString(),
	// "");
	// }
	// }
	// config.updateAppStateToken(SchedulerConfig.openmrs_syncer_sync_schedule_tracker_by_last_update_enrollment,
	// end);
	// } catch (Exception e) {
	// e.printStackTrace();
	// }
	// }

	@MotechListener(subjects = OpenmrsConstants.SCHEDULER_OPENMRS_DATA_PUSH_SUBJECT)
	public void pushToOpenMRS(MotechEvent event) {
		System.out.println("144 start data send to openmrs");
		if (!lock.tryLock()) {
			logger.warn("Not fetching forms from Message Queue. It is already in progress.");
			System.out.println("Not fetching forms from Message Queue. It is already in progress.");
			return;
		}
		try {
			reSyncToOpenMRS();
			logger("156 - RUNNING ", event.getSubject());
			AppStateToken lastsync = config
					.getAppStateTokenByName(SchedulerConfig.openmrs_syncer_sync_client_by_date_updated);
			System.out.println("158 - NEXT RUNNING lastsync = "+ lastsync);
			Long start = lastsync == null || lastsync.getValue() == null ? 0 : lastsync.longValue();
			System.out.println("START FROM LAST SYNC: "+ start);
			List<Client> cl = clientService.findByServerVersion(start);
			System.out.println("Clients list size " + cl.size());
			for (Client c : cl) {
				try {
					System.out.println("163 - PUSH CLIENT REVISED");
					pushClientRevised(c);
				} catch (RelationshipNotFoundException rnfException) {
					config.updateAppStateToken(SchedulerConfig.openmrs_syncer_sync_client_by_date_updated, c.getServerVersion());
					errorTraceService.log("OPENMRS FAILED CLIENT PUSH", Client.class.getName(), c.getBaseEntityId(),
							ExceptionUtils.getStackTrace(rnfException), "");
				} catch (IdentifierNotFoundException infException){
					config.updateAppStateToken(SchedulerConfig.openmrs_syncer_sync_client_by_date_updated, c.getServerVersion());
					errorTraceService.log("OPENMRS FAILED CLIENT PUSH", Client.class.getName(), c.getBaseEntityId(),
							ExceptionUtils.getStackTrace(infException), "");
				} catch (HouseholdNotFoundException hnfException){
					config.updateAppStateToken(SchedulerConfig.openmrs_syncer_sync_client_by_date_updated, c.getServerVersion());
					errorTraceService.log("OPENMRS FAILED CLIENT PUSH", Client.class.getName(), c.getBaseEntityId(),
							ExceptionUtils.getStackTrace(hnfException), "");
				} catch (Exception ex1) {
					config.updateAppStateToken(SchedulerConfig.openmrs_syncer_sync_client_by_date_updated, c.getServerVersion());
					errorTraceService.log("OPENMRS FAILED CLIENT PUSH", Client.class.getName(), c.getBaseEntityId(),
							ExceptionUtils.getStackTrace(ex1), "");
					//ex1.printStackTrace();
					logger.error("client error message:" + ex1.getMessage() + ", and cause :" + ex1.getCause()
							+ ", baseEntityId:" + c.getBaseEntityId());
				}
			}

			System.out.println("RUNNING FOR EVENTS");

			lastsync = config.getAppStateTokenByName(SchedulerConfig.openmrs_syncer_sync_event_by_date_updated);
			start = lastsync == null || lastsync.getValue() == null ? 0 : lastsync.longValue();
//			pushEvent(start);
			logger("PUSH TO OPENMRS FINISHED AT ", "");

		}
		catch (Exception ex) {
			ex.printStackTrace();
		}
		finally {
			lock.unlock();
		}
	}

	public DateTime logger(String message, String subject) {
		System.out.println(message + subject + " at " + DateTime.now());
		return DateTime.now();

	}

	public JSONObject pushClientRevised(Client c)
			throws JSONException, IOException, RelationshipNotFoundException, IdentifierNotFoundException,
			HouseholdNotFoundException {
		JSONObject patient = new JSONObject();
		if (c.getAttributes().containsKey("spouseName") || c.getRelationships() == null) {
			if (c.getBirthdate() == null) {
				c.setBirthdate(new DateTime("1970-01-01"));
			}
			if (c.getAttributes().containsKey("spouseName")) {
				c.setGender("Female");
			}
		}
		String isSendToOpenMRS = c.getIsSendToOpenMRS();
		String uuid = c.getIdentifiers().get(patientService.OPENMRS_UUID_IDENTIFIER_TYPE);

		System.out.println("UUID:-> "+ uuid);

		if (uuid == null) {
			System.out.println("BASE ENTITY ID: "+ c.getBaseEntityId());
			System.out.println("isSendToOpenMRS: "+ isSendToOpenMRS);
//			FIXME -- omitted, followed by proshanto sarker. will be active when atomfeed is activated
			//			if (isSendToOpenMRS == null || isSendToOpenMRS.equalsIgnoreCase("yes")) {
				JSONObject patientJson = patientService.createPatientRevised(c, uuid); //save or update at openmrs
				System.out.println("END TIME: "+System.currentTimeMillis());
				System.out.println("Patient RESPONSE:-> "+ patientJson);
				String patientUuid = (patientJson != null && patientJson.getJSONObject("patient").has("uuid")) ?
						patientJson.getJSONObject("patient").getString("uuid"):null;
				if (!org.apache.commons.lang.StringUtils.isBlank(patientUuid)) {
					c.addIdentifier(PatientService.OPENMRS_UUID_IDENTIFIER_TYPE, patientUuid);
					clientService.addOrUpdate(c, false);
				}
				patient = patientJson;
//			}
		} else {
//			FIXME -- omitted, followed by proshanto sarker. will be active when atomfeed is activated
			System.out.println("Updating patient " + uuid);
			//String isSendToOpenMRS = c.getIsSendToOpenMRS();
//			if (isSendToOpenMRS == null || isSendToOpenMRS.equalsIgnoreCase("yes")) {
				JSONObject patientJson = patientService.createPatientRevised(c, uuid);
				System.out.println("UPDATE END TIME: "+System.currentTimeMillis());
				System.out.println("Patient RESPONSE:-> "+ patientJson);
				patient = patientJson;
//			} else {
//				System.out.println("this client doesn't go to openMRS at baseEntityId: " + uuid);
//			}

			config.updateAppStateToken(SchedulerConfig.openmrs_syncer_sync_client_by_date_updated,
					c.getServerVersion());
		}
		config.updateAppStateToken(SchedulerConfig.openmrs_syncer_sync_client_by_date_updated, c.getServerVersion());
		return patient;
	}

	public JSONObject pushClient(long start) throws JSONException {
		List<Client> cl = clientService.findByServerVersion(start);
		System.out.println("Clients list size " + cl.size());
		JSONObject patient = new JSONObject();// only for test code purpose
		JSONArray patientsJsonArray = new JSONArray();// only for test code purpose
		JSONArray relationshipsArray = new JSONArray();// only for test code purpose
		JSONObject returnJsonObject = new JSONObject();// only for test code purpose
		for (Client c : cl) {
			try {
				Multimedia multiMedia = multimediaService.findByCaseId(c.getBaseEntityId());
				// FIXME This is to deal with existing records and should be
				// removed later
				if (c.getAttributes().containsKey("spouseName") || c.getRelationships() == null) {
					if (c.getBirthdate() == null) {
						c.setBirthdate(new DateTime("1970-01-01"));
					}
					if (c.getAttributes().containsKey("spouseName")) {
						c.setGender("Female");
					}
				}
				String uuid = c.getIdentifier(PatientService.OPENMRS_UUID_IDENTIFIER_TYPE);

				if (uuid == null) {
					JSONObject p = patientService.getPatientByIdentifier(c.getBaseEntityId());
					for (Entry<String, String> id : c.getIdentifiers().entrySet()) {
						p = patientService.getPatientByIdentifier(id.getValue());
						if (p != null) {
							break;
						}
					}
					if (p != null) {
						uuid = p.getString("uuid");
					}
				}

				String isSendToOpenMRS = c.getIsSendToOpenMRS();
				if (uuid != null) {
					System.out.println("Updating patient " + uuid);
					//String isSendToOpenMRS = c.getIsSendToOpenMRS();
					if (isSendToOpenMRS == null || isSendToOpenMRS.equalsIgnoreCase("yes")) {
						patient = patientService.updatePatient(c, uuid);
					} else {
						System.out.println("this client doesn't go to openMRS at baseEntityId: " + uuid);
					}

					config.updateAppStateToken(SchedulerConfig.openmrs_syncer_sync_client_by_date_updated,
							c.getServerVersion());
					if (multiMedia != null) {
						patientService.personImageUpload(multiMedia, uuid);
					}

				} else {
					if (isSendToOpenMRS == null || isSendToOpenMRS.equalsIgnoreCase("yes")) {
						JSONObject patientJson = patientService.createPatient(c);
						patient = patientJson;
						if (patientJson != null && patientJson.has("uuid")) {
							c.addIdentifier(PatientService.OPENMRS_UUID_IDENTIFIER_TYPE, patientJson.getString("uuid"));
							clientService.addOrUpdate(c, false);
							config.updateAppStateToken(SchedulerConfig.openmrs_syncer_sync_client_by_date_updated,
									c.getServerVersion());
							if (multiMedia != null) {
								patientService.personImageUpload(multiMedia, patientJson.getString("uuid"));
							}

						}
					} else {
						// data not sent to openMRS but timestamp is updated
						config.updateAppStateToken(SchedulerConfig.openmrs_syncer_sync_client_by_date_updated,
								c.getServerVersion());
					}
				}
			}
			catch (Exception ex1) {
				config.updateAppStateToken(SchedulerConfig.openmrs_syncer_sync_client_by_date_updated, c.getServerVersion());
				errorTraceService.log("OPENMRS FAILED CLIENT PUSH", Client.class.getName(), c.getBaseEntityId(),
						ExceptionUtils.getStackTrace(ex1), "");
				//ex1.printStackTrace();
				logger.error("client error message:" + ex1.getMessage() + ", and cause :" + ex1.getCause()
						+ ", baseEntityId:" + c.getBaseEntityId());
			}
			patientsJsonArray.put(patient);
		}

		for (Client c : cl) {
			String isSendToOpenMRS = c.getIsSendToOpenMRS();
			if (c.getRelationships() != null && isSendToOpenMRS.equalsIgnoreCase("yes")) {// Mother has no relations.
				try {
					JSONObject motherJson = patientService.getPatientByIdentifier(c.getRelationships().get("household")
							.get(0).toString());
					JSONObject person = motherJson.getJSONObject("person");

					if (person.getString("uuid") != null) {
						JSONArray relationships = patientService.getPersonRelationShip(c.getIdentifier("OPENMRS_UUID"));
						if (relationships.length() == 0) {
							JSONObject relation = patientService.createPatientRelationShip(c.getIdentifier("OPENMRS_UUID"),
									person.getString("uuid"), "03ed3084-4c7a-11e5-9192-080027b662ec");
							relationshipsArray.put(relation); // only for test code purpose
							System.out.println("RelationshipsCreated check openmrs" + c.getIdentifier("OPENMRS_UUID"));
						} else {
							System.out.println("Relationship already created");
						}
					}

					/*List<Client> siblings = clientService.findByRelationship(c.getRelationships().get("mother").get(0)
					        .toString());
					if (!siblings.isEmpty() || siblings != null) {
						JSONObject siblingJson;
						JSONObject sibling;
						for (Client client : siblings) {
							if (!c.getBaseEntityId().equals(client.getBaseEntityId())) {
								siblingJson = patientService.getPatientByIdentifier(client.getBaseEntityId());
								sibling = siblingJson.getJSONObject("person");
								patientService.createPatientRelationShip(c.getIdentifier("OPENMRS_UUID"),
								    sibling.getString("uuid"), "8d91a01c-c2cc-11de-8d13-0010c6dffd0f");
							}

						}

					}*/
				}
				catch (Exception e) {
					logger.error("no relationship found at case id " + c.getBaseEntityId());
				}

			}
			System.out.println("RelationshipsCreated sibling1 ");

		}
		returnJsonObject.put("patient", patientsJsonArray); // only for test code purpose
		returnJsonObject.put("relation", relationshipsArray);// only for test code purpose
		return returnJsonObject;

	}

	public JSONObject pushEvent(long start) {
		List<Event> el = eventService.findByServerVersion(start);
		System.out.println("Event list size " + el.size() + " [start]" + start);
		JSONObject encounter = null;
		for (Event e : el) {
			try {
				String uuid = e.getIdentifier(EncounterService.OPENMRS_UUID_IDENTIFIER_TYPE);
				String isSendToOpenMRS = e.getIsSendToOpenMRS();
				if (uuid != null) {
					if (isSendToOpenMRS.equalsIgnoreCase("yes") || isSendToOpenMRS == null) {
						encounter = encounterService.updateEncounter(e);

					} else {
						System.out.println("this event doesn't go to openMRS at baseentityid: " + uuid + ", and event type:"
								+ e.getEventType());
					}

				} else {
					if (isSendToOpenMRS == null || isSendToOpenMRS.equalsIgnoreCase("yes")) {
						JSONObject eventJson = encounterService.createEncounter(e);
						encounter = eventJson;
						if (eventJson != null && eventJson.has("uuid")) {
							e.addIdentifier(EncounterService.OPENMRS_UUID_IDENTIFIER_TYPE, eventJson.getString("uuid"));
							eventService.updateEvent(e);
						}
					}
				}
				config.updateAppStateToken(SchedulerConfig.openmrs_syncer_sync_event_by_date_updated, e.getServerVersion());
			}
			catch (Exception ex2) {
				config.updateAppStateToken(SchedulerConfig.openmrs_syncer_sync_event_by_date_updated, e.getServerVersion());
				errorTraceService.log("OPENMRS FAILED EVENT PUSH", Event.class.getName(), e.getId(),
						ExceptionUtils.getStackTrace(ex2), "");
				//ex2.printStackTrace();
				logger.error("event error message:" + ex2.getMessage() + ", and cause :" + ex2.getCause()
						+ ", baseEntityId:" + e.getBaseEntityId());
			}
		}
		return encounter;

	}

	//	@MotechListener(subjects = OpenmrsConstants.SCHEDULER_OPENMRS_DATA_PUSH_RESYNC_SUBJECT)
	public void reSyncToOpenMRS() {
		System.out.println("404 start data send to openmrs");
		List<org.opensrp.domain.ErrorTrace> errorTraces = errorTraceService.findAllUnSyncErrors("org.opensrp.domain.Client");
		System.out.println("Error trace list size " + errorTraces.size());
		for (org.opensrp.domain.ErrorTrace errorTrace : errorTraces) {
			System.out.println("START TIME: "+System.currentTimeMillis());
			Client c = clientService.find(errorTrace.getRecordId());
			try {
				pushClientRevised(c);
				errorTrace.setStatus("solved");
				errorTrace.setDateOccurred(new DateTime());
				errorTraceService.updateError(errorTrace);
			} catch (RelationshipNotFoundException rnfException){
				config.updateAppStateToken(SchedulerConfig.openmrs_syncer_sync_client_by_date_updated,
						c.getServerVersion());
				logger.error("client error message:" + rnfException.getMessage() + ", and cause :" + rnfException.getErrorMessage()
						+ ",at baseEntityId:" + errorTrace.getRecordId());
				errorTrace.setStackTrace(rnfException.getErrorMessage());
				errorTrace.setStatus("unsolved");
				errorTrace.setDateOccurred(new DateTime());
				errorTraceService.updateError(errorTrace);
			} catch (IdentifierNotFoundException infException){
				config.updateAppStateToken(SchedulerConfig.openmrs_syncer_sync_client_by_date_updated,
						c.getServerVersion());
				logger.error("client error message:" + infException.getMessage() + ", and cause :" + infException.getErrorMessage()
						+ ",at baseEntityId:" + errorTrace.getRecordId());
				errorTrace.setDateOccurred(new DateTime());
				errorTrace.setStackTrace(infException.getErrorMessage());
				errorTrace.setStatus("unsolved");
				errorTraceService.updateError(errorTrace);
			} catch (HouseholdNotFoundException hnfException){
				config.updateAppStateToken(SchedulerConfig.openmrs_syncer_sync_client_by_date_updated,
						c.getServerVersion());
				logger.error("client error message:" + hnfException.getMessage() + ", and cause :" + hnfException.getErrorMessage()
						+ ",at baseEntityId:" + errorTrace.getRecordId());
				errorTrace.setDateOccurred(new DateTime());
				errorTrace.setStatus("unsolved");
				errorTrace.setStackTrace(hnfException.getErrorMessage());
				errorTraceService.updateError(errorTrace);
			} catch (Exception e) {
				config.updateAppStateToken(SchedulerConfig.openmrs_syncer_sync_client_by_date_updated,
						c.getServerVersion());
				logger.error("client error message:" + e.getMessage() + ", and cause :" + e.getCause()
						+ ",at baseEntityId:" + errorTrace.getRecordId());
				errorTrace.setStatus("unsolved");
				errorTrace.setDateOccurred(new DateTime());
				errorTraceService.updateError(errorTrace);
			}
		}
	}

}
