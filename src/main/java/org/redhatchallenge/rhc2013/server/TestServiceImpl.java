package org.redhatchallenge.rhc2013.server;

import au.com.bytecode.opencsv.CSVReader;
import com.google.gwt.user.server.rpc.RemoteServiceServlet;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.shiro.SecurityUtils;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.redhatchallenge.rhc2013.client.TestService;
import org.redhatchallenge.rhc2013.shared.CorrectAnswer;
import org.redhatchallenge.rhc2013.shared.Question;
import org.redhatchallenge.rhc2013.shared.Student;
import org.redhatchallenge.rhc2013.shared.TimeIsUpException;
import org.redhatchallenge.rhc2013.shared.TimeslotExpiredException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/**
 * @author: Terry Chia (terrycwk1994@gmail.com)
 */
public class TestServiceImpl extends RemoteServiceServlet implements TestService {

    private Map<Integer, Question> questionMapEn;
    private Map<Integer, Question> questionMapCh;
    private Map<String, Integer> scoreMap = new HashMap<String, Integer>();
    private Map<String, int[]> assignedQuestionsMap = new HashMap<String, int[]>();

    public TestServiceImpl() {
        InputStream inEn = TestServiceImpl.class.getResourceAsStream("/en.csv");
        InputStream inCh = TestServiceImpl.class.getResourceAsStream("/ch.csv");

        questionMapEn = parseCSV(inEn);
        questionMapCh = parseCSV(inCh);
    }

    @Override
    public List<Question> loadQuestions() throws IllegalArgumentException, TimeslotExpiredException {

        Session session = HibernateUtil.getSessionFactory().getCurrentSession();

        try {
            String id = SecurityUtils.getSubject().getPrincipal().toString();
            session.beginTransaction();
            Student student = (Student)session.get(Student.class, Integer.parseInt(id));

            if(System.currentTimeMillis() > student.getTimeslot() && System.currentTimeMillis() < student.getTimeslot() + 3600000) {
                if(!assignedQuestionsMap.containsKey(id)) {
                    student.setStartTime(new Timestamp(System.currentTimeMillis()));
                    session.update(student);
                    session.getTransaction().commit();

                    class TimesUp extends TimerTask {

                        private final int studentId;

                        TimesUp(int id) {
                            this.studentId = id;
                        }

                        @Override
                        public void run() {
                            Session session = HibernateUtil.getSessionFactory().getCurrentSession();
                            try {
                                session.beginTransaction();
                                Student student = (Student)session.get(Student.class, studentId);
                                if(student.getEndTime() == null) {
                                    student.setEndTime(new Timestamp(System.currentTimeMillis()));
                                }
                                session.update(student);
                                session.getTransaction().commit();
                            } catch (HibernateException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    Timer timer = new Timer();
                    timer.schedule(new TimesUp(student.getContestantId()), 60000);

                    assignedQuestionsMap.put(id, student.getQuestions());
                    return getQuestionsFromListOfQuestionNumbers(student.getQuestions(), student.getLanguage());
                }

                else {
                    return getQuestionsFromListOfQuestionNumbers(assignedQuestionsMap.get(id), student.getLanguage());
                }
            }

            else {
                throw new TimeslotExpiredException();
            }

        } catch (HibernateException e) {
            session.getTransaction().rollback();
            throw new RuntimeException("Failed to retrieve profile information from the database");
        }
    }

    @Override
    public boolean submitAnswer(int id, Set<CorrectAnswer> answers) throws IllegalArgumentException, TimeIsUpException {

        Session session = HibernateUtil.getSessionFactory().getCurrentSession();
        try {
            String studentId = SecurityUtils.getSubject().getPrincipal().toString();
            session.beginTransaction();
            Student student = (Student)session.get(Student.class, Integer.parseInt(studentId));

            int[] array = assignedQuestionsMap.get(studentId);
            array = ArrayUtils.removeElement(array, id);
            assignedQuestionsMap.put(studentId, array);

            if(student.getEndTime() == null) {
                if(compare(id, answers)) {
                    updateScore(true);
                    return true;
                }

                else {
                    updateScore(false);
                    return false;
                }
            }

            else {
                throw new TimeIsUpException();
            }
        } catch (HibernateException e) {
            throw new RuntimeException("Failed to retrieve profile information from the database");
        } finally {
            session.close();
        }
    }

    @Override
    public int getScore() throws IllegalArgumentException {
        String id = SecurityUtils.getSubject().getPrincipal().toString();
        int score = scoreMap.get(id);
        flushScoreToDatabase(id);
        return score;
    }

    private boolean compare(int id, Set<CorrectAnswer> provided) {
        Set<CorrectAnswer> correctAnswers = questionMapEn.get(id).getCorrectAnswers();
        return provided.equals(correctAnswers);
    }

    private void updateScore(boolean correct) {

        String id = SecurityUtils.getSubject().getPrincipal().toString();
        if(!scoreMap.containsKey(id)) {
            scoreMap.put(id, 0);
        }

        int score = scoreMap.get(id);

        if(correct) {
            score += 2;
        }

        else {
            score -= 1;
        }

        scoreMap.put(id, score);
    }


    private Map<Integer, Question> parseCSV(InputStream in) {

        HashMap<Integer, Question> map = new HashMap<>();

        try {
            CSVReader reader = new CSVReader(new InputStreamReader(in, "UTF-8"));
            String[] nextLine;
            while ((nextLine = reader.readNext()) != null) {
                Question question = new Question();
                question.setId(Integer.parseInt(nextLine[0]));
                question.setQuestion(nextLine[3]);

                List<String> answers = new ArrayList<String>(4);
                answers.add(nextLine[4]);
                answers.add(nextLine[5]);
                answers.add(nextLine[6]);
                answers.add(nextLine[7]);
                question.setAnswers(answers);

                Set<CorrectAnswer> correctAnswers = new HashSet<CorrectAnswer>(4);
                String[] parts = nextLine[8].split(",");
                for (String s : parts) {
                    int selection = Integer.parseInt(s);
                    switch (selection) {
                        case 1:
                            correctAnswers.add(CorrectAnswer.ONE);
                            break;
                        case 2:
                            correctAnswers.add(CorrectAnswer.TWO);
                            break;
                        case 3:
                            correctAnswers.add(CorrectAnswer.THREE);
                            break;
                        case 4:
                            correctAnswers.add(CorrectAnswer.FOUR);
                    }
                }

                question.setCorrectAnswers(correctAnswers);

                map.put(question.getId(), question);
            }

            return map;

        } catch (IOException e) {
            throw new RuntimeException("Unable to parse input stream");
        }
    }

    private List<Question> getQuestionsFromListOfQuestionNumbers(int[] questionNumberArray, String language) {
        List<Question> listOfQuestions = new ArrayList<>(150);

        if (language.equals("English")) {
            for (int i : questionNumberArray) {
                listOfQuestions.add(questionMapEn.get(i));
            }
        }

        else if (language.equals("Chinese (Simplified)")) {
            for (int i : questionNumberArray) {
                listOfQuestions.add(questionMapCh.get(i));
            }
        }

        return listOfQuestions;
    }

    private void flushScoreToDatabase(String id) {

        int score = scoreMap.get(id);

        Session session = HibernateUtil.getSessionFactory().getCurrentSession();

        try {
            session.beginTransaction();
            Student student = (Student)session.get(Student.class, Integer.parseInt(id));
            student.setScore(score);
            student.setEndTime(new Timestamp(System.currentTimeMillis()));
            session.getTransaction().commit();
            scoreMap.remove(id);
            assignedQuestionsMap.remove(id);
        } catch (HibernateException e) {
            e.printStackTrace();
            session.getTransaction().rollback();
        }
    }
}