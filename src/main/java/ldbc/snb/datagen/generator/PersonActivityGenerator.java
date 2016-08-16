
package ldbc.snb.datagen.generator;

import ldbc.snb.datagen.dictionary.Dictionaries;
import ldbc.snb.datagen.objects.*;
import ldbc.snb.datagen.serializer.PersonActivityExporter;
import ldbc.snb.datagen.serializer.PersonActivitySerializer;
import ldbc.snb.datagen.serializer.UpdateEventSerializer;
import ldbc.snb.datagen.util.FactorTable;
import ldbc.snb.datagen.util.RandomGeneratorFarm;
import ldbc.snb.datagen.vocabulary.SN;

import org.apache.hadoop.mapreduce.Reducer.Context;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Random;

public class PersonActivityGenerator {

	private ForumGenerator forumGenerator_ = null;
	private RandomGeneratorFarm randomFarm_ = null;
	private UniformPostGenerator uniformPostGenerator_ = null;
	private FlashmobPostGenerator flashmobPostGenerator_ = null;
	private PhotoGenerator photoGenerator_ = null;
	private CommentGenerator commentGenerator_ = null;
	private LikeGenerator likeGenerator_ = null;
	private PersonActivitySerializer personActivitySerializer_ = null;
	private UpdateEventSerializer updateSerializer_ = null;
	private long forumId = 0;
	private long messageId = 0;
    private FactorTable factorTable_;
    private PersonActivityExporter exporter_;

	public PersonActivityGenerator( PersonActivitySerializer serializer, UpdateEventSerializer updateSerializer ) {
		randomFarm_ = new RandomGeneratorFarm();
		personActivitySerializer_ = serializer;
		updateSerializer_ = updateSerializer;
		forumGenerator_ = new ForumGenerator();
		TextGenerator generator = new LdbcSnbTextGenerator(randomFarm_.get(RandomGeneratorFarm.Aspect.LARGE_TEXT), Dictionaries.tags);
        likeGenerator_ = new LikeGenerator();
        commentGenerator_ = new CommentGenerator(generator, likeGenerator_);
		uniformPostGenerator_ = new UniformPostGenerator(generator, commentGenerator_, likeGenerator_);
		flashmobPostGenerator_ = new FlashmobPostGenerator(generator, commentGenerator_, likeGenerator_);
		photoGenerator_ = new PhotoGenerator(likeGenerator_);
        factorTable_ = new FactorTable();
        exporter_ = new PersonActivityExporter(personActivitySerializer_, updateSerializer_, factorTable_);
	}

	private void generateActivity( Person person, ArrayList<Person> block ) throws IOException {
        try {
            factorTable_.extractFactors(person);
            generateWall(person, block);
            generateGroups(person, block);
            generateAlbums(person, block);
        } catch (AssertionError e) {
            System.out.println("Assertion error when generating activity!");
            System.out.println(e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
	}

	public void reset() {
		personActivitySerializer_.reset();
	}

	private void generateWall( Person person, ArrayList<Person> block ) throws IOException {
		// generate wall
		Forum wall = forumGenerator_.createWall(randomFarm_, forumId++, person);
		exporter_.export(wall);
		for( ForumMembership fm : wall.memberships()) {
			exporter_.export(fm);
		}

		// generate wall posts
		ForumMembership personMembership = new ForumMembership(wall.id(),
			wall.creationDate()+DatagenParams.deltaTime,  new Person.PersonSummary(person)
		);
		ArrayList<ForumMembership> fakeMembers = new ArrayList<ForumMembership>();
		fakeMembers.add(personMembership);
		messageId = uniformPostGenerator_.createPosts(randomFarm_, wall, fakeMembers , numPostsPerGroup(randomFarm_, wall, DatagenParams.maxNumPostPerMonth, DatagenParams.maxNumFriends), messageId, exporter_);
		messageId = flashmobPostGenerator_.createPosts(randomFarm_, wall, fakeMembers, numPostsPerGroup(randomFarm_, wall, DatagenParams.maxNumFlashmobPostPerMonth, DatagenParams.maxNumFriends), messageId, exporter_ );
	}

	private void generateGroups( Person person, ArrayList<Person> block ) throws IOException  {
		// generate user created groups
		double moderatorProb = randomFarm_.get(RandomGeneratorFarm.Aspect.FORUM_MODERATOR).nextDouble();
		if (moderatorProb <= DatagenParams.groupModeratorProb) {
			int numGroup = randomFarm_.get(RandomGeneratorFarm.Aspect.NUM_FORUM).nextInt(DatagenParams.maxNumGroupCreatedPerUser)+1;
			for (int j = 0; j < numGroup; j++) {
				Forum group = forumGenerator_.createGroup(randomFarm_, forumId++, person, block);
				exporter_.export(group);

				for( ForumMembership fm : group.memberships()) {
					exporter_.export(fm);
				}

				// generate uniform posts/comments
				messageId = uniformPostGenerator_.createPosts(randomFarm_, group, group.memberships(), numPostsPerGroup(randomFarm_, group, DatagenParams.maxNumGroupPostPerMonth, DatagenParams.maxNumMemberGroup), messageId, exporter_);
				messageId = flashmobPostGenerator_.createPosts(randomFarm_, group, group.memberships(), numPostsPerGroup(randomFarm_, group, DatagenParams.maxNumGroupFlashmobPostPerMonth, DatagenParams.maxNumMemberGroup),messageId, exporter_);
			}
		}

	}

	private void generateAlbums(Person person, ArrayList<Person> block ) throws IOException {
		// generate albums
		int numOfmonths = (int) Dictionaries.dates.numberOfMonths(person);
		int numPhotoAlbums = randomFarm_.get(RandomGeneratorFarm.Aspect.NUM_PHOTO_ALBUM).nextInt(DatagenParams.maxNumPhotoAlbumsPerMonth+1);
		if (numOfmonths != 0) {
			numPhotoAlbums = numOfmonths * numPhotoAlbums;
		}
		for (int i = 0; i < numPhotoAlbums; i++) {
			Forum album = forumGenerator_.createAlbum(randomFarm_, forumId++, person, i);
			exporter_.export(album);

			for( ForumMembership fm : album.memberships()) {
				exporter_.export(fm);
			}

			ForumMembership personMembership = new ForumMembership(album.id(),
				album.creationDate()+DatagenParams.deltaTime,  new Person.PersonSummary(person)
			);
			ArrayList<ForumMembership> fakeMembers = new ArrayList<ForumMembership>();
			fakeMembers.add(personMembership);
			int numPhotos = randomFarm_.get(RandomGeneratorFarm.Aspect.NUM_PHOTO).nextInt(DatagenParams.maxNumPhotoPerAlbums+1);
			messageId = photoGenerator_.createPhotos(randomFarm_, album, fakeMembers, numPhotos, messageId, exporter_);
		}
	}
	
	private int numPostsPerGroup( RandomGeneratorFarm randomFarm, Forum forum, int maxPostsPerMonth, int maxMembersPerForum ) {
		Random random = randomFarm.get(RandomGeneratorFarm.Aspect.NUM_POST);
		int numOfmonths = (int) Dictionaries.dates.numberOfMonths(forum.creationDate());
		int numberPost = 0;
		if (numOfmonths == 0) {
			numberPost = random.nextInt(maxPostsPerMonth+1);
		} else {
			numberPost = random.nextInt(maxPostsPerMonth * numOfmonths+1);
		}
		return (numberPost * forum.memberships().size()) / maxMembersPerForum;
	}
	
	public void generateActivityForBlock( int seed, ArrayList<Person> block, Context context ) throws IOException {
		randomFarm_.resetRandomGenerators(seed);
		forumId = 0;
		messageId = 0;
		SN.machineId = seed;
		personActivitySerializer_.reset();
		int counter = 0;
        float personGenerationTime = 0.0f;
        long initTime = System.currentTimeMillis();
		for( Person p : block ) {
			long start = System.currentTimeMillis();
			generateActivity(p, block);
			if( DatagenParams.updateStreams ) {
				updateSerializer_.changePartition();
			}
			if( counter % 1000 == 0 ) {
				context.setStatus("Generating activity of person "+counter+" of block"+seed);
				context.progress();
			}
            float time = (System.currentTimeMillis() - start)/1000.0f;
            personGenerationTime+=time;
            //System.out.println("Time to generate activity for person "+counter+": "+time+". Throughput "+counter/((System.currentTimeMillis() - initTime)*0.001));
			counter++;
		}
        System.out.println("Average person activity generation time "+personGenerationTime / (float)block.size());
	}


    public void writeActivityFactors( OutputStream writer) {
        factorTable_.writeActivityFactors(writer);
    }

	public void writePersonFactors( OutputStream writer) {
		factorTable_.writePersonFactors(writer);
	}
}
