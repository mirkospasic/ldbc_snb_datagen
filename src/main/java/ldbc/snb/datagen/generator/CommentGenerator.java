/*
* To change this license header, choose License Headers in Project Properties.
* To change this template file, choose Tools | Templates
* and open the template in the editor.
*/
package ldbc.snb.datagen.generator;

import ldbc.snb.datagen.dictionary.Dictionaries;
import ldbc.snb.datagen.objects.*;
import ldbc.snb.datagen.serializer.PersonActivityExporter;
import ldbc.snb.datagen.util.RandomGeneratorFarm;
import ldbc.snb.datagen.vocabulary.SN;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Properties;
import java.util.TreeSet;

/**
 *
 * @author aprat
 */
public class CommentGenerator {
	private String[] shortComments_ = {"ok", "good", "great", "cool", "thx", "fine", "LOL", "roflol", "no way!", "I see", "right", "yes", "no", "duh", "thanks", "maybe"};
	private String[] gifs_ = {"heart.gif", "love.gif", "question.gif", "like.gif", "ily.gif", "crazy.gif", "imy.gif", "kiss.gif", "sick.gif", "sad.gif", "happy.gif", "lol.gif"};
	private TextGenerator generator;
	private LikeGenerator likeGenerator_;
    private Comment comment_;
	/* A set of random number generator for different purposes.*/
	
	public CommentGenerator(TextGenerator generator, LikeGenerator likeGenerator){
		this.generator = generator;
		this.likeGenerator_ = likeGenerator;
        this.comment_ = new Comment();
	}
	
	public long createComments(RandomGeneratorFarm randomFarm, final Forum forum, final Post post, long numComments, long startId, PersonActivityExporter exporter){
		long nextId = startId;
		ArrayList<Message> replyCandidates = new ArrayList<Message>();
		replyCandidates.add(post);

		Properties prop = new Properties();
		prop.setProperty("type","comment");
		for( int i = 0; i < numComments; ++i ) {
			int replyIndex = randomFarm.get(RandomGeneratorFarm.Aspect.REPLY_TO).nextInt(replyCandidates.size());
			Message replyTo = replyCandidates.get(replyIndex);
			ArrayList<ForumMembership> validMemberships = new ArrayList<ForumMembership>();
			for( ForumMembership fM : forum.memberships()) {
				if (fM.creationDate()+DatagenParams.deltaTime <= replyTo.creationDate()){
					validMemberships.add(fM);
				}
			}
			if (validMemberships.size() == 0) {
				return nextId;
			}
			ForumMembership member = validMemberships.get(randomFarm.get(RandomGeneratorFarm.Aspect.MEMBERSHIP_INDEX).nextInt(validMemberships.size()));
			TreeSet<Integer> tags = new TreeSet<Integer>();
			String content = "";
			String gif = "";
			

			boolean isShort = false;
			if( randomFarm.get(RandomGeneratorFarm.Aspect.REDUCED_TEXT).nextDouble() > 0.6666) {

				ArrayList<Integer> currentTags = new ArrayList<Integer>();
				Iterator<Integer> it = replyTo.tags().iterator();
				while(it.hasNext()) {
					Integer tag = it.next();
					if( randomFarm.get(RandomGeneratorFarm.Aspect.TAG).nextDouble() > 0.5) {
						tags.add(tag);
					}
					currentTags.add(tag);
				}
				
				for( int j = 0; j < (int)Math.ceil(replyTo.tags().size() / 2.0); ++j) {
					int randomTag = currentTags.get(randomFarm.get(RandomGeneratorFarm.Aspect.TAG).nextInt(currentTags.size()));
					tags.add(Dictionaries.tagMatrix.getRandomRelated(randomFarm.get(RandomGeneratorFarm.Aspect.TOPIC), randomTag));
				}
				content = this.generator.generateText(member.person(), tags,prop);
			} else {
				isShort = true;
				if (randomFarm.get(RandomGeneratorFarm.Aspect.COMMENT_ISGIF).nextDouble() > 0.8) {
				    int index = randomFarm.get(RandomGeneratorFarm.Aspect.TEXT_SIZE).nextInt(shortComments_.length);
				    content = shortComments_[index];
				}
				else {
				    int index = randomFarm.get(RandomGeneratorFarm.Aspect.COMMENT_GIF).nextInt(gifs_.length);
				    gif = gifs_[index];
				}
			}

			
			long creationDate = Dictionaries.dates.powerlawCommDateDay(randomFarm.get(RandomGeneratorFarm.Aspect.DATE),replyTo.creationDate()+DatagenParams.deltaTime);
			/*if( creationDate <= Dictionaries.dates.getEndDateTime() )*/ {
				Comment comment = new Comment(SN.formId(SN.composeId(nextId++,creationDate)),
					creationDate,
					member.person(),
					forum.id(),
					content,
					tags,
					Dictionaries.ips.getIP(randomFarm.get(RandomGeneratorFarm.Aspect.IP), randomFarm.get(RandomGeneratorFarm.Aspect.DIFF_IP), randomFarm.get(RandomGeneratorFarm.Aspect.DIFF_IP_FOR_TRAVELER), member.person().ipAddress(), creationDate),
					Dictionaries.browsers.getPostBrowserId(randomFarm.get(RandomGeneratorFarm.Aspect.DIFF_BROWSER), randomFarm.get(RandomGeneratorFarm.Aspect.BROWSER), member.person().browserId()),
					post.messageId(),
					replyTo.messageId(),
					gif);
				if (randomFarm.get(RandomGeneratorFarm.Aspect.COMMENT_COUNTRY).nextDouble() > 0.02)
				    comment.countryKnown(false);
				if(!isShort) replyCandidates.add(new Comment(comment));
				exporter.export(comment);
				if( comment.content().length() > 10 && randomFarm.get(RandomGeneratorFarm.Aspect.NUM_LIKE).nextDouble() <= 0.1 ) {
					likeGenerator_.generateLikes(randomFarm.get(RandomGeneratorFarm.Aspect.NUM_LIKE), forum, comment, Like.LikeType.COMMENT, exporter);
				}
			}
		}
		replyCandidates.clear();
		return nextId;
	}
	
}
